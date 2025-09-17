/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.servlet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.solr.SolrJettyTestBase;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.Test;

public abstract class CacheHeaderTestBase extends SolrJettyTestBase {

  protected Response executeRequest(HttpMethod method, String... params) throws Exception {
    Fields queryParams = new Fields();
    if (params.length == 0) {
      queryParams.add("q", "solr");
      queryParams.add("qt", "standard");
    }
    for (int i = 0; i < params.length / 2; i++) {
      queryParams.add(params[i * 2], params[i * 2 + 1]);
    }

    String url = getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?" + 
                 queryParams.encode(StandardCharsets.UTF_8);
    
    return getHttpClient().newRequest(url).method(method)
        .timeout(10, TimeUnit.SECONDS)
        .send();
  }

  protected Response executeUpdateRequest(HttpMethod method, String... params) throws Exception {
    Fields queryParams = new Fields();
    for (int i = 0; i < params.length / 2; i++) {
      queryParams.add(params[i * 2], params[i * 2 + 1]);
    }

    String url = getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/update?" + 
                 queryParams.encode(StandardCharsets.UTF_8);
    
    return getHttpClient().newRequest(url).method(method)
        .timeout(10, TimeUnit.SECONDS)
        .send();
  }

  protected void checkResponseBody(String method, Response response) throws Exception {
    String responseBody = response.getContentAsString();

    if ("GET".equals(method)) {
      switch (response.getStatus()) {
        case HttpStatus.OK_200:
          assertTrue(
              "Response body was empty for method " + method,
              responseBody != null && responseBody.length() > 0);
          break;
        case HttpStatus.NOT_MODIFIED_304:
          assertTrue(
              "Response body was not empty for method " + method,
              responseBody == null || responseBody.length() == 0);
          break;
        case HttpStatus.PRECONDITION_FAILED_412:
          assertTrue(
              "Response body was not empty for method " + method,
              responseBody == null || responseBody.length() == 0);
          break;
        default:
          System.err.println(responseBody);
          assertEquals("Unknown request response", 0, response.getStatus());
      }
    }
    if ("HEAD".equals(method)) {
      assertTrue(
          "Response body was not empty for method " + method,
          responseBody == null || responseBody.length() == 0);
    }
  }

  // The tests
  @Test
  public void testLastModified() throws Exception {
    doLastModified("GET");
    doLastModified("HEAD");
  }

  @Test
  public void testEtag() throws Exception {
    doETag("GET");
    doETag("HEAD");
  }

  @Test
  public void testCacheControl() throws Exception {
    doCacheControl("GET");
    doCacheControl("HEAD");
    doCacheControl("POST");
  }

  protected abstract void doCacheControl(String method) throws Exception;

  protected abstract void doETag(String method) throws Exception;

  protected abstract void doLastModified(String method) throws Exception;
}
