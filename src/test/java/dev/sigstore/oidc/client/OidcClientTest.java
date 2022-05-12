/*
 * Copyright 2022 The Sigstore Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sigstore.oidc.client;

import com.gargoylesoftware.htmlunit.WebClient;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import java.io.IOException;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.OAuth2Config;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OidcClientTest {

  private MockOAuth2Server server;

  @Before
  public void setUpServer() throws IOException {
    // TODO: Remove custom config key once (https://github.com/navikt/mock-oauth2-server/issues/247)
    // is updated
    String json =
        Resources.toString(
            Resources.getResource("dev/sigstore/oidc/server/config.json"), Charsets.UTF_8);
    var cfg = OAuth2Config.Companion.fromJson(json);
    server = new MockOAuth2Server(cfg);
    server.start();
  }

  @After
  public void shutdownServer() throws IOException {
    server.shutdown();
  }

  @Test
  public void testAuthFlow() throws IOException, OidcException {
    var issuerId = "test-default";

    try (var webClient = new WebClient()) {
      var oidcClient =
          OidcClient.builder()
              .setIssuer(server.issuerUrl(issuerId).toString())
              .setBrowser(webClient::getPage)
              .build();

      var eid = oidcClient.getIDToken(null);
      Assert.assertEquals("test.person@test.com", eid.getEmailAddress());
    }
  }
}