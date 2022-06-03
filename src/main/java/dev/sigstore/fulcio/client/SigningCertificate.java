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
package dev.sigstore.fulcio.client;

import com.google.api.client.util.PemReader;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.sigstore.json.GsonSupplier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.conscrypt.ct.DigitallySigned;
import org.conscrypt.ct.SerializationException;
import org.conscrypt.ct.SignedCertificateTimestamp;

/**
 * Response from Fulcio that includes a Certificate Chain and a Signed Certificate Timestamp (SCT).
 *
 * <p>An SCT is not required for all instances of fulcio, however the public good instance of fulcio
 * should probably always include one. SCT can be associated with a certificate in two modes:
 *
 * <p>In detached mode -- fulcio provides the SCT via a fulcio specific non-standard header in the
 * response as base64 encoded json.
 *
 * <p>In embedded mode -- fulcio generates certificates with the SCT embedded as an extension in the
 * x509 certificates (this is the most common form, and we should expect this moving forward)
 */
public class SigningCertificate {
  private static final String SCT_X509_OID = "1.3.6.1.4.1.11129.2.4.2";

  private final CertPath certPath;
  @Nullable private final SignedCertificateTimestamp sct;

  static SigningCertificate newSigningCertificate(String certs, @Nullable String sctHeader)
      throws CertificateException, IOException, SerializationException {
    CertPath certPath = decodeCerts(certs);
    if (sctHeader != null) {
      SignedCertificateTimestamp sct = decodeSCT(sctHeader);
      return new SigningCertificate(certPath, sct);
    }
    return new SigningCertificate(certPath, null);
  }

  @VisibleForTesting
  static CertPath decodeCerts(String content) throws CertificateException, IOException {
    PemReader pemReader = new PemReader(new StringReader(content));
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    ArrayList<X509Certificate> certList = new ArrayList<>();
    while (true) {
      PemReader.Section section = pemReader.readNextSection();
      if (section == null) {
        break;
      }

      byte[] certBytes = section.getBase64DecodedBytes();
      certList.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes)));
    }
    if (certList.isEmpty()) {
      throw new CertificateParsingException(
          "no valid PEM certificates were found in response from Fulcio");
    }

    return cf.generateCertPath(certList);
  }

  @VisibleForTesting
  static SignedCertificateTimestamp decodeSCT(String sctHeader) throws SerializationException {
    byte[] sct = Base64.getDecoder().decode(sctHeader);
    Gson gson = new GsonSupplier().get();
    return gson.fromJson(
            new InputStreamReader(new ByteArrayInputStream(sct), StandardCharsets.UTF_8),
            SctJson.class)
        .toSct();
  }

  /** Returns true if the signing certificate constains an SCT embedded in the X509 extensions. */
  boolean hasEmbeddedSct() {
    return getLeafCertificate().getExtensionValue(SCT_X509_OID) != null;
  }

  private static class SctJson {
    private int sct_version;
    private byte[] id;
    private long timestamp;
    private byte[] extensions;
    private byte[] signature;

    public SignedCertificateTimestamp toSct() throws JsonParseException, SerializationException {
      if (sct_version != 0) {
        throw new JsonParseException(
            "Invalid SCT version:" + sct_version + ", only 0 (V1) is allowed");
      }
      if (extensions.length != 0) {
        throw new JsonParseException(
            "SCT has extensions that cannot be handled by client:" + new String(extensions));
      }

      DigitallySigned digiSig = DigitallySigned.decode(signature);
      return new SignedCertificateTimestamp(
          SignedCertificateTimestamp.Version.V1,
          id,
          timestamp,
          extensions,
          digiSig,
          SignedCertificateTimestamp.Origin.OCSP_RESPONSE);
    }
  }

  private SigningCertificate(CertPath certPath, @Nullable SignedCertificateTimestamp sct) {
    this.certPath = certPath;
    this.sct = sct;
  }

  public CertPath getCertPath() {
    return certPath;
  }

  @SuppressWarnings("unchecked")
  public List<X509Certificate> getCertificates() {
    return (List<X509Certificate>) certPath.getCertificates();
  }

  public X509Certificate getLeafCertificate() {
    return (X509Certificate) certPath.getCertificates().get(0);
  }

  Optional<SignedCertificateTimestamp> getDetachedSct() {
    return Optional.ofNullable(sct);
  }
}
