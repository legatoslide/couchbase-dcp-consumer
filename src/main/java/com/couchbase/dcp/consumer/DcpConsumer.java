/**
 * Copyright (C) 2015 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.dcp.consumer;

import com.couchbase.client.core.ClusterFacade;
import com.couchbase.client.core.CouchbaseCore;
import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.message.cluster.GetClusterConfigRequest;
import com.couchbase.client.core.message.cluster.GetClusterConfigResponse;
import com.couchbase.client.core.message.cluster.OpenBucketRequest;
import com.couchbase.client.core.message.cluster.SeedNodesRequest;
import com.couchbase.client.core.message.dcp.*;
import com.couchbase.client.deps.io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Func1;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Sergey Avseyev
 * @author David Ostrovsky
 */
public class DcpConsumer implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DcpConsumer.class);
    private final DcpConsumerEnvironment env;
    private final ClusterFacade core;
    private final String couchbaseBucket;
    private final List<String> couchbaseNodes;

    public DcpConsumer(final List<String> couchbaseNodes, final String couchbaseBucket) {
        this(couchbaseNodes, couchbaseBucket, DefaultDcpConsumerEnvironment.create());
    }

    public DcpConsumer(final String couchbaseNodes, final String couchbaseBucket) {
        this(splitNodes(couchbaseNodes), couchbaseBucket,
                DefaultDcpConsumerEnvironment.create());
    }

    public DcpConsumer(final String couchbaseNodes, final String couchbaseBucket, DcpConsumerEnvironment env) {
        this(splitNodes(couchbaseNodes), couchbaseBucket, env);
    }

    public DcpConsumer(final List<String> couchbaseNodes, final String couchbaseBucket, DcpConsumerEnvironment env) {
        this.env = env;
        this.couchbaseBucket = couchbaseBucket;
        this.couchbaseNodes = couchbaseNodes;

        core = new CouchbaseCore(this.env);
    }

    @Override
    public String toString() {
        return "CouchbaseProducer{" +
                "couchbaseBucket=" + couchbaseBucket +
                '}';
    }

    @Override
    public void run() {
        core.send(new SeedNodesRequest(couchbaseNodes))
                .timeout(2, TimeUnit.SECONDS)
                .toBlocking()
                .single();
        core.send(new OpenBucketRequest(couchbaseBucket, ""))
                .timeout(20, TimeUnit.SECONDS)
                .toBlocking()
                .single();

        String streamName = couchbaseBucket + "->" + "sout";
        core.send(new OpenConnectionRequest(streamName, couchbaseBucket))
                .toList()
                .flatMap(couchbaseResponses -> partitionSize())
                .flatMap(this::requestStreams)
                .toBlocking()
                .forEach(this::handleMessage);
    }

    protected void handleMessage(DCPRequest request) {
        String key = null;
        String content = null;
        if (request instanceof MutationMessage) {
            MutationMessage message = (MutationMessage) request;
            key = message.key();
            content = message.content().toString(CharsetUtil.UTF_8);
            // handle document change/update
        } else if (request instanceof RemoveMessage) {
            key = ((RemoveMessage) request).key();
            // handle document deletion
        }
        System.out.println(request.toString() + " -> " + key + " : " + content);
    }

    private static String joinNodes(final List<String> nodes) {
        StringBuilder sb = new StringBuilder();
        int size = nodes.size();

        for (int i = 0; i < size; ++i) {
            sb.append(nodes.get(i));
            if (i < size - 1) {
                sb.append(",");
            }
        }

        return sb.toString();
    }

    private static List<String> splitNodes(final String nodes) {
        return Arrays.asList(nodes.split(","));
    }

    private Observable<Integer> partitionSize() {
        return core
            .<GetClusterConfigResponse>send(new GetClusterConfigRequest())
                .map(new Func1<GetClusterConfigResponse, Integer>() {
                    @Override
                    public Integer call(GetClusterConfigResponse response) {
                        CouchbaseBucketConfig config = (CouchbaseBucketConfig) response
                                .config().bucketConfig(couchbaseBucket);
                        return config.numberOfPartitions();
                    }
                });
    }

    private Observable<DCPRequest> requestStreams(int numberOfPartitions) {
        return Observable.merge(
            Observable.range(0, numberOfPartitions)
                    .flatMap(new Func1<Integer, Observable<StreamRequestResponse>>() {
                        @Override
                        public Observable<StreamRequestResponse> call(Integer partition) {
                            return core.send(new StreamRequestRequest(partition.shortValue(), couchbaseBucket));
                        }
                    })
                    .map(new Func1<StreamRequestResponse, Observable<DCPRequest>>() {
                        @Override
                        public Observable<DCPRequest> call(StreamRequestResponse response) {
                            return response.stream();
                        }
                    })
        );
    }
}
