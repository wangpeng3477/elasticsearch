/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.packaging.util;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class ServerUtils {

    protected static final Logger logger =  LogManager.getLogger(ServerUtils.class);

    // generous timeout  as nested virtualization can be quite slow ...
    private static final long waitTime = TimeUnit.MINUTES.toMillis(
        System.getProperty("tests.inVM") == null ? 3 : 1
    );
    private static final long timeoutLength = TimeUnit.SECONDS.toMillis(
        System.getProperty("tests.inVM") == null ? 30 : 10
    );

    public static void waitForElasticsearch(Installation installation) throws IOException {
        waitForElasticsearch("green", null, installation);
    }

    public static void waitForElasticsearch(String status, String index, Installation installation) throws IOException {

        Objects.requireNonNull(status);

        // we loop here rather than letting httpclient handle retries so we can measure the entire waiting time
        final long startTime = System.currentTimeMillis();
        long timeElapsed = 0;
        boolean started = false;
        while (started == false && timeElapsed < waitTime) {
            try {

                final HttpResponse response = Request.Get("http://localhost:9200/_cluster/health")
                    .connectTimeout((int) timeoutLength)
                    .socketTimeout((int) timeoutLength)
                    .execute()
                    .returnResponse();

                if (response.getStatusLine().getStatusCode() >= 300) {
                    final String statusLine = response.getStatusLine().toString();
                    final String body = EntityUtils.toString(response.getEntity());
                    throw new RuntimeException("Connecting to elasticsearch cluster health API failed:\n" + statusLine+ "\n" + body);
                }

                started = true;

            } catch (IOException e) {
                logger.info("Got exception when waiting for cluster health", e);
            }

            timeElapsed = System.currentTimeMillis() - startTime;
        }

        if (started == false) {
            if (installation != null) {
                FileUtils.logAllLogs(installation.logs, logger);
            }
            throw new RuntimeException("Elasticsearch did not start");
        }

        final String url;
        if (index == null) {
            url = "http://localhost:9200/_cluster/health?wait_for_status=" + status + "&timeout=60s&pretty";
        } else {
            url = "http://localhost:9200/_cluster/health/" + index + "?wait_for_status=" + status + "&timeout=60s&pretty";

        }

        final String body = makeRequest(Request.Get(url));
        assertThat("cluster health response must contain desired status", body, containsString(status));
    }

    public static void runElasticsearchTests() throws IOException {
        makeRequest(
            Request.Post("http://localhost:9200/library/_doc/1?refresh=true&pretty")
                .bodyString("{ \"title\": \"Book #1\", \"pages\": 123 }", ContentType.APPLICATION_JSON));

        makeRequest(
            Request.Post("http://localhost:9200/library/_doc/2?refresh=true&pretty")
                .bodyString("{ \"title\": \"Book #2\", \"pages\": 456 }", ContentType.APPLICATION_JSON));

        String count = makeRequest(Request.Get("http://localhost:9200/_count?pretty"));
        assertThat(count, containsString("\"count\" : 2"));

        makeRequest(Request.Delete("http://localhost:9200/_all"));
    }

    public static String makeRequest(Request request) throws IOException {
        final HttpResponse response = request.execute().returnResponse();
        final String body = EntityUtils.toString(response.getEntity());

        if (response.getStatusLine().getStatusCode() >= 300) {
            throw new RuntimeException("Request failed:\n" + response.getStatusLine().toString() + "\n" + body);
        }

        return body;

    }
}
