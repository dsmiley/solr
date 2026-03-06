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
package org.apache.solr.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.solr.client.api.model.CreateCollectionRequestBody;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.IOUtils;

/**
 * {@link SolrBackend} that connects to a pre-existing remote Solr node. The caller supplies the
 * base URL at construction time (e.g. {@code http://host:port/solr}).
 *
 * <p>Can be subclassed to add a startup step (e.g. launching a Docker container) before
 * constructing the URL.
 */
public class RemoteSolrBackend implements SolrBackend {

  private final HttpJettySolrClient adminClient;

  public RemoteSolrBackend(String baseUrl) {
    this.adminClient = new HttpJettySolrClient.Builder(baseUrl).build();
  }

  @Override
  public SolrClient newClient(String collection) {
    return new HttpJettySolrClient.Builder(adminClient.getBaseURL())
        .withDefaultCollection(collection)
        .build();
  }

  @Override
  public SolrClient getAdminClient() {
    return adminClient;
  }

  @Override
  public void registerConfigset(Path configDir, String name)
      throws SolrException, SolrBackend.AlreadyExistsException {
    try {
      List<String> existing = new ConfigSetAdminRequest.List().process(adminClient).getConfigSets();
      if (existing != null && existing.contains(name)) {
        throw new SolrBackend.AlreadyExistsException(name);
      }
      Path tempZip = Files.createTempFile("configset-", ".zip");
      try {
        zipDirectory(configDir, tempZip);
        new ConfigSetAdminRequest.Upload()
            .setConfigSetName(name)
            .setUploadFile(tempZip, "application/zip")
            .process(adminClient);
      } finally {
        Files.deleteIfExists(tempZip);
      }
    } catch (AlreadyExistsException | SolrException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void createCollection(CreateCollectionRequestBody body)
      throws SolrBackend.AlreadyExistsException, SolrException {
    try {
      CollectionAdminRequest.Create create =
          CollectionAdminRequest.createCollection(
              body.name,
              body.config,
              body.numShards != null ? body.numShards : 1,
              body.replicationFactor != null ? body.replicationFactor : 1);
      if (body.properties != null && !body.properties.isEmpty()) {
        create.setProperties(body.properties);
      }
      create.process(adminClient);
    } catch (SolrException e) {
      AlreadyExistsException.rethrowIfAlreadyExists(e, body.name);
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /** Packages {@code sourceDir} contents into a zip file. */
  private static void zipDirectory(Path sourceDir, Path targetZip) throws IOException {
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
      Files.walk(sourceDir)
          .filter(Files::isRegularFile)
          .forEach(
              file -> {
                try {
                  zos.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString()));
                  Files.copy(file, zos);
                  zos.closeEntry();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(adminClient);
  }
}
