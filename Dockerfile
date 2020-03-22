FROM alpine:latest

RUN apk --no-cache add openjdk8 openjdk8-jre
WORKDIR /tmp/chime-streaming-transcribe/
ADD ./build/distributions/amazon-chime-voiceconnector-recordandtranscribe.zip /tmp/chime-streaming-transcribe/
RUN unzip /tmp/chime-streaming-transcribe/amazon-chime-voiceconnector-recordandtranscribe.zip && rm amazon-chime-voiceconnector-recordandtranscribe.zip