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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.SuppressForbidden;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.junit.BeforeClass;
import org.junit.Test;

/** A test case for the several HTTP cache headers emitted by Solr */
public class CacheHeaderTest extends CacheHeaderTestBase {

  @BeforeClass
  public static void beforeTest() throws Exception {
    System.setProperty(
        "solr.requests.streaming.remote.enabled", "true"); // needed for testCacheVetoHandler

    Path solrHomeDirectory = createTempDir();
    setupJettyTestHome(solrHomeDirectory, "collection1");
    createAndStartJetty(solrHomeDirectory);
  }

  protected static final String CONTENTS = "id\n100\n101\n102";

  @Test
  public void testCacheVetoHandler() throws Exception {
    Path f = makeFile(CacheHeaderTest.CONTENTS, StandardCharsets.UTF_8.name());
    Fields queryParams = new Fields();
    queryParams.add(CommonParams.STREAM_FILE, f.toRealPath().toString());
    queryParams.add(CommonParams.STREAM_CONTENTTYPE, "text/csv");
    
    String url = getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/update?" + 
                 queryParams.encode(StandardCharsets.UTF_8);
    
    Response response = getHttpClient().newRequest(url)
        .method(HttpMethod.GET)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    assertEquals(HttpStatus.OK_200, response.getStatus());
    checkVetoHeaders(response, true);
    Files.delete(f);
  }

  @Test
  public void testCacheVetoException() throws Exception {
    Fields queryParams = new Fields();
    queryParams.add("q", "xyz_ignore_exception:solr");
    queryParams.add("qt", "standard");
    
    String url = getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?" + 
                 queryParams.encode(StandardCharsets.UTF_8);
    
    // We force an exception from Solr. This should emit "no-cache" HTTP headers
    Response response = getHttpClient().newRequest(url)
        .method(HttpMethod.GET)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    assertNotEquals(HttpStatus.OK_200, response.getStatus());
    checkVetoHeaders(response, false);
  }

  @SuppressForbidden(reason = "Needs currentTimeMillis to check against expiry headers from Solr")
  protected void checkVetoHeaders(Response response, boolean checkExpires) throws Exception {
    String cacheControl = response.getHeaders().get("Cache-Control");
    assertNotNull("We got no Cache-Control header", cacheControl);
    assertTrue(
        "We got no no-cache in the Cache-Control header [" + cacheControl + "]",
        cacheControl.contains("no-cache"));
    assertTrue(
        "We got no no-store in the Cache-Control header [" + cacheControl + "]",
        cacheControl.contains("no-store"));

    String pragma = response.getHeaders().get("Pragma");
    assertNotNull("We got no Pragma header", pragma);
    assertEquals("no-cache", pragma);

    if (checkExpires) {
      String expires = response.getHeaders().get("Expires");
      assertNotNull("We got no Expires header", expires);
      // Parse the date using HTTP date format
      Instant d = parseHttpDate(expires);
      assertTrue(
          "We got no Expires header far in the past",
          System.currentTimeMillis() - d.toEpochMilli() > 100000);
    }
  }

  private Instant parseHttpDate(String dateStr) {
    // HTTP date formats (RFC 7231)
    DateTimeFormatter[] formatters = {
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz"),
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy")
    };
    
    for (DateTimeFormatter formatter : formatters) {
      try {
        return Instant.from(formatter.parse(dateStr));
      } catch (Exception e) {
        // Try next format
      }
    }
    throw new IllegalArgumentException("Unable to parse date: " + dateStr);
  }

  @Override
  protected void doLastModified(String method) throws Exception {
    // We do a first request to get the last modified
    // This must result in a 200 OK response
    Response response = executeRequest(HttpMethod.valueOf(method));
    checkResponseBody(method, response);

    assertEquals(
        "Got no response code 200 in initial request",
        HttpStatus.OK_200,
        response.getStatus());

    String lastModified = response.getHeaders().get("Last-Modified");
    assertNotNull("We got no Last-Modified header", lastModified);

    Instant lastModifiedDate = parseHttpDate(lastModified);

    // If-Modified-Since tests
    String currentDateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
        Instant.now().atZone(ZoneId.of("GMT")));
    
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Modified-Since", currentDateStr)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "Expected 304 NotModified response with current date",
        HttpStatus.NOT_MODIFIED_304,
        response.getStatus());

    String pastDateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
        lastModifiedDate.minusSeconds(10).atZone(ZoneId.of("GMT")));
    
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Modified-Since", pastDateStr)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "Expected 200 OK response with If-Modified-Since in the past",
        HttpStatus.OK_200,
        response.getStatus());

    // If-Unmodified-Since tests
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Unmodified-Since", pastDateStr)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "Expected 412 Precondition failed with If-Unmodified-Since in the past",
        HttpStatus.PRECONDITION_FAILED_412,
        response.getStatus());

    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Unmodified-Since", currentDateStr)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "Expected 200 OK response with If-Unmodified-Since and current date",
        HttpStatus.OK_200,
        response.getStatus());
  }

  // test ETag
  @Override
  protected void doETag(String method) throws Exception {
    Response response = executeRequest(HttpMethod.valueOf(method));
    checkResponseBody(method, response);

    assertEquals(
        "Got no response code 200 in initial request",
        HttpStatus.OK_200,
        response.getStatus());

    String etag = response.getHeaders().get("ETag");
    assertNotNull("We got no ETag in the response", etag);
    assertTrue(
        "Not a valid ETag", etag.startsWith("\"") && etag.endsWith("\""));

    // If-None-Match tests
    // we set a non-matching ETag
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-None-Match", "\"xyz123456\"")
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-None-Match: Got no response code 200 in response to non matching ETag",
        HttpStatus.OK_200,
        response.getStatus());

    // now we set matching ETags
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-None-Match", "\"xyz1223\", \"1231323423\", \"1211211\", " + etag)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-None-Match: Got no response 304 to matching ETag",
        HttpStatus.NOT_MODIFIED_304,
        response.getStatus());

    // we now set the special star ETag
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-None-Match", "*")
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-None-Match: Got no response 304 for star ETag",
        HttpStatus.NOT_MODIFIED_304,
        response.getStatus());

    // If-Match tests
    // we set a non-matching ETag
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Match", "\"xyz123456\"")
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-Match: Got no response code 412 in response to non matching ETag",
        HttpStatus.PRECONDITION_FAILED_412,
        response.getStatus());

    // now we set matching ETags
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Match", "\"xyz1223\", \"1231323423\", \"1211211\", " + etag)
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-Match: Got no response 200 to matching ETag",
        HttpStatus.OK_200,
        response.getStatus());

    // now we set the special star ETag
    response = getHttpClient().newRequest(getBaseUrl() + "/" + DEFAULT_TEST_COLLECTION_NAME + "/select?q=solr&qt=standard")
        .method(HttpMethod.valueOf(method))
        .header("If-Match", "*")
        .timeout(10, TimeUnit.SECONDS)
        .send();
    checkResponseBody(method, response);
    assertEquals(
        "If-Match: Got no response 200 to star ETag",
        HttpStatus.OK_200,
        response.getStatus());
  }

  @Override
  protected void doCacheControl(String method) throws Exception {
    if ("POST".equals(method)) {
      Response response = executeRequest(HttpMethod.POST);
      checkResponseBody(method, response);

      String cacheControl = response.getHeaders().get("Cache-Control");
      assertNull("We got a cache-control header in response to POST", cacheControl);

      String expires = response.getHeaders().get("Expires");
      assertNull("We got an Expires  header in response to POST", expires);
    } else {
      Response response = executeRequest(HttpMethod.valueOf(method));
      checkResponseBody(method, response);

      String cacheControl = response.getHeaders().get("Cache-Control");
      assertNotNull("We got no cache-control header", cacheControl);

      String expires = response.getHeaders().get("Expires");
      assertNotNull("We got no Expires header in response", expires);
    }
  }

  protected Path makeFile(String contents, String charset) {
    try {
      Path f = createTempFile("cachetest", "csv");
      Files.writeString(f, contents, Charset.forName(charset));
      return f;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
