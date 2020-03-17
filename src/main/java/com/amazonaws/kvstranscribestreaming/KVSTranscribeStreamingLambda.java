package com.amazonaws.kvstranscribestreaming;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.transcribestreaming.KVSByteToAudioEventSubscription;
import com.amazonaws.transcribestreaming.StreamTranscriptionBehaviorImpl;
import com.amazonaws.transcribestreaming.TranscribeStreamingRetryClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.LanguageCode;
import software.amazon.awssdk.services.transcribestreaming.model.MediaEncoding;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.amazonaws.kvstranscribestreaming.AudioUtils.S3_CLIENT_CONNECTION_TIMEOUT_MILLIS;
import static com.amazonaws.kvstranscribestreaming.AudioUtils.S3_CLIENT_CLIENT_EXECUTION_TIMEOUT_MILLIS;

/**
 * Demonstrate Amazon VoiceConnectors's real-time transcription feature using
 * AWS Kinesis Video Streams and AWS Transcribe. The data flow is :
 * <p>
 * Amazon CloudWatch Events => Amazon SQS => AWS Lambda => AWS Transcribe => AWS
 * DynamoDB & S3
 *
 * <p>
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * </p>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public class KVSTranscribeStreamingLambda implements RequestHandler<SQSEvent, String> {

    private static final Regions REGION = Regions.fromName(System.getenv("AWS_REGION"));
    private static final Regions TRANSCRIBE_REGION = Regions.fromName(System.getenv("AWS_REGION"));
    private static final String TRANSCRIBE_ENDPOINT = "https://transcribestreaming." + TRANSCRIBE_REGION.getName()
            + ".amazonaws.com";
    private static final String RECORDINGS_BUCKET_NAME = System.getenv("RECORDINGS_BUCKET_NAME");
    private static final String IS_TRANSCRIBE_ENABLED = System.getenv("IS_TRANSCRIBE_ENABLED");
    private static final String RECORDINGS_KEY_PREFIX = "voiceConnectorToKVS_";
    private static final boolean CONSOLE_LOG_TRANSCRIPT_FLAG = true;
    private static final boolean RECORDINGS_PUBLIC_READ_ACL = false;

    private static final Logger logger = LoggerFactory.getLogger(KVSTranscribeStreamingLambda.class);
    public static final MetricsUtil metricsUtil = new MetricsUtil(AmazonCloudWatchClientBuilder.defaultClient());
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // SegmentWriter saves Transcription segments to DynamoDB
    private TranscribedSegmentWriter segmentWriter = null;

    private static final DynamoDB dynamoDB = new DynamoDB(
            AmazonDynamoDBClientBuilder.standard().withRegion(REGION.getName()).build());

    /**
     * Handler function for the Lambda
     *
     * @param event
     * @param context
     * @return
     */
    @Override
    public String handleRequest(SQSEvent event, Context context) {

        try {
            logger.info("received request : " + objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            logger.error("Error happened where serializing the event", e);
        }
        logger.info("received context: " + context.toString());

        try {
            for (SQSEvent.SQSMessage sqsMessage : event.getRecords()) {
                Map<String, Object> snsMessage = objectMapper.readValue(sqsMessage.getBody(), Map.class);

                Map<String, String> detail = (Map) snsMessage.get("detail");

                final String streamARN = detail.get("streamArn");
                final String firstFragementNumber = detail.get("startFragmentNumber");
                final String transactionId = detail.get("transactionId");
                final String callId = detail.get("callId");
                final String streamingStatus = detail.get("streamingStatus");

                if (streamingStatus.equals("STARTED")) {

                    logger.info("Received STARTED event");

                    // create a SegmentWriter to be able to save off transcription results
                    segmentWriter = new TranscribedSegmentWriter(transactionId, dynamoDB, CONSOLE_LOG_TRANSCRIPT_FLAG);

                    startKVSToTranscribeStreaming(streamARN, firstFragementNumber, transactionId,
                            Boolean.valueOf(IS_TRANSCRIBE_ENABLED), true, callId);
                    
                    logger.info("Finished processing request");
                }
            }

        } catch (Exception e) {
            logger.error("KVS to Transcribe Streaming failed with: ", e);
            return "{ \"result\": \"Failed\" }";
        }
        return "{ \"result\": \"Success\" }";
    }

    /**
     * Starts streaming between KVS and Transcribe The transcript segments are
     * continuously saved to the Dynamo DB table At end of the streaming session,
     * the raw audio is saved as an s3 object
     *
     * @param streamName
     * @param startFragmentNum
     * @param transactionId
     * @param callId
     * @throws Exception
     */
    private void startKVSToTranscribeStreaming(String streamName, String startFragmentNum, String transactionId,
            boolean transcribeEnabled, boolean shouldWriteAudioToFile, final String callId) throws Exception {

        Path saveAudioFilePath = Paths.get("/tmp",
                transactionId + "_" + callId + "_" + DATE_FORMAT.format(new Date()) + ".raw");
        FileOutputStream fileOutputStream = new FileOutputStream(saveAudioFilePath.toString());

        InputStream kvsInputStream = KVSUtils.getInputStreamFromKVS(streamName, REGION, startFragmentNum,
                getAWSCredentials());
        StreamingMkvReader streamingMkvReader = StreamingMkvReader
                .createDefault(new InputStreamParserByteSource(kvsInputStream));

        FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor = new FragmentMetadataVisitor.BasicMkvTagProcessor();
        FragmentMetadataVisitor fragmentVisitor = FragmentMetadataVisitor.create(Optional.of(tagProcessor));

        int kvsTimeoutSecond = 900 - S3_CLIENT_CLIENT_EXECUTION_TIMEOUT_MILLIS / 1000 - S3_CLIENT_CONNECTION_TIMEOUT_MILLIS / 1000;
        if (transcribeEnabled) {
            try (TranscribeStreamingRetryClient client = new TranscribeStreamingRetryClient(getTranscribeCredentials(),
                    TRANSCRIBE_ENDPOINT, TRANSCRIBE_REGION, metricsUtil)) {

                logger.info("Calling Transcribe service..");

                CompletableFuture<Void> result = client.startStreamTranscription(
                        // since we're definitely working with telephony audio, we know that's 8 kHz
                        getRequest(8000),
                        new KVSAudioStreamPublisher(streamingMkvReader, transactionId, fileOutputStream, tagProcessor,
                                fragmentVisitor, shouldWriteAudioToFile),
                        new StreamTranscriptionBehaviorImpl(segmentWriter));

                result.get(kvsTimeoutSecond, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.debug("Timing out KVS to Transcribe Streaming after {} sec", kvsTimeoutSecond);

            } catch (Exception e) {
                logger.error("Error during streaming: ", e);
                throw e;

            } finally {
                if (shouldWriteAudioToFile) {
                    closeFileAndUploadRawAudio(kvsInputStream, fileOutputStream, saveAudioFilePath, transactionId);
                }
            }
        } else {
            try {
                logger.info("Transcribe is not enabled; saving audio bytes to location");

                // Write audio bytes from the KVS stream to the temporary file
                ByteBuffer audioBuffer = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor,
                        tagProcessor, transactionId);
                while (audioBuffer.remaining() > 0) {
                    byte[] audioBytes = new byte[audioBuffer.remaining()];
                    audioBuffer.get(audioBytes);
                    fileOutputStream.write(audioBytes);
                    audioBuffer = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor,
                            transactionId);
                }

            } finally {
                closeFileAndUploadRawAudio(kvsInputStream, fileOutputStream, saveAudioFilePath, transactionId);
            }
        }
    }

    /**
     * Closes the FileOutputStream and uploads the Raw audio file to S3
     *
     * @param kvsInputStream
     * @param fileOutputStream
     * @param saveAudioFilePath
     * @param transactionId
     * @throws IOException
     */
    private void closeFileAndUploadRawAudio(InputStream kvsInputStream, FileOutputStream fileOutputStream,
            Path saveAudioFilePath, String transactionId) throws IOException {

        kvsInputStream.close();
        fileOutputStream.close();

        // Upload the Raw Audio file to S3
        if (new File(saveAudioFilePath.toString()).length() > 0) {
            AudioUtils.uploadRawAudio(REGION, RECORDINGS_BUCKET_NAME, RECORDINGS_KEY_PREFIX,
                    saveAudioFilePath.toString(), transactionId, RECORDINGS_PUBLIC_READ_ACL, getAWSCredentials());
        } else {
            logger.info("Skipping upload to S3. Audio file has 0 bytes: " + saveAudioFilePath);
        }
    }

    /**
     * @return AWS credentials to be used to connect to s3 (for fetching and
     *         uploading audio) and KVS
     */
    private static AWSCredentialsProvider getAWSCredentials() {
        return DefaultAWSCredentialsProviderChain.getInstance();
    }

    /**
     * @return AWS credentials to be used to connect to Transcribe service. This
     *         example uses the default credentials provider, which looks for
     *         environment variables (AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY)
     *         or a credentials file on the system running this program.
     */
    private static AwsCredentialsProvider getTranscribeCredentials() {
        return DefaultCredentialsProvider.create();
    }

    /**
     * Build StartStreamTranscriptionRequestObject containing required parameters to
     * open a streaming transcription request, such as audio sample rate and
     * language spoken in audio
     *
     * @param mediaSampleRateHertz sample rate of the audio to be streamed to the
     *                             service in Hertz
     * @return StartStreamTranscriptionRequest to be used to open a stream to
     *         transcription service
     */
    private static StartStreamTranscriptionRequest getRequest(Integer mediaSampleRateHertz) {
        return StartStreamTranscriptionRequest.builder().languageCode(LanguageCode.EN_US.toString())
                .mediaEncoding(MediaEncoding.PCM).mediaSampleRateHertz(mediaSampleRateHertz).build();
    }

    /**
     * KVSAudioStreamPublisher implements audio stream publisher. It emits audio
     * events from a KVS stream asynchronously in a separate thread
     */
    private static class KVSAudioStreamPublisher implements Publisher<AudioStream> {
        private final StreamingMkvReader streamingMkvReader;
        private String callId;
        private OutputStream outputStream;
        private FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor;
        private FragmentMetadataVisitor fragmentVisitor;
        private boolean shouldWriteToOutputStream;

        private KVSAudioStreamPublisher(StreamingMkvReader streamingMkvReader, String callId, OutputStream outputStream,
                FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor, FragmentMetadataVisitor fragmentVisitor,
                boolean shouldWriteToOutputStream) {
            this.streamingMkvReader = streamingMkvReader;
            this.callId = callId;
            this.outputStream = outputStream;
            this.tagProcessor = tagProcessor;
            this.fragmentVisitor = fragmentVisitor;
            this.shouldWriteToOutputStream = shouldWriteToOutputStream;
        }

        @Override
        public void subscribe(Subscriber<? super AudioStream> s) {
            s.onSubscribe(new KVSByteToAudioEventSubscription(s, streamingMkvReader, callId, outputStream, tagProcessor,
                    fragmentVisitor, shouldWriteToOutputStream));
        }
    }

}
