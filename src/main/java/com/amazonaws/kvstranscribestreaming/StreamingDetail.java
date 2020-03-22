package com.amazonaws.kvstranscribestreaming;

/**
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
public class StreamingDetail {
    private String streamARN;
    private String firstFragementNumber;
    private String transactionId;
    private String callId;
    private String streamingStatus;
    private String startTime;

    public StreamingDetail(final String streamARN,
                           final String firstFragementNumber,
                           final String transactionId,
                           final String callId,
                           final String streamingStatus,
                           final String startTime
    ) {
        this.streamARN = streamARN;
        this.firstFragementNumber = firstFragementNumber;
        this.transactionId = transactionId;
        this.callId = callId;
        this.streamingStatus = streamingStatus;
        this.startTime = startTime;
    }

    public String streamARN() {
        return streamARN;
    }

    public String firstFragementNumber() {
        return firstFragementNumber;
    }

    public String transactionId() {
        return transactionId;
    }

    public String callId() {
        return callId;
    }

    public String streamingStatus() {
        return streamingStatus;
    }

    public String startTime() {
        return startTime;
    }

    static class builder {
        private String streamARN;
        private String firstFragementNumber;
        private String transactionId;
        private String callId;
        private String streamingStatus;
        private String startTime;

        public builder() {
        }

        public builder streamARN(final String streamARN) {
            this.streamARN = streamARN;
            return this;
        }

        public builder firstFragementNumber(final String firstFragementNumber) {
            this.firstFragementNumber = firstFragementNumber;
            return this;
        }

        public builder transactionId(final String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public builder callId(final String callId) {
            this.callId = callId;
            return this;
        }

        public builder streamingStatus(final String streamingStatus) {
            this.streamingStatus = streamingStatus;
            return this;
        }

        public builder startTime(final String startTime) {
            this.startTime = startTime;
            return this;
        }

        public StreamingDetail build() {
            return new StreamingDetail(streamARN, firstFragementNumber, transactionId, callId, streamingStatus, startTime);
        }
    }
}
