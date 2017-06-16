/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.http.impl.service.server.grizzly;

import static org.mule.runtime.http.api.HttpHeaders.Names.CONTENT_DISPOSITION;
import static org.mule.runtime.http.api.HttpHeaders.Names.CONTENT_TYPE;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.core.api.util.IOUtils;
import org.mule.runtime.http.api.domain.entity.HttpEntity;
import org.mule.runtime.http.api.domain.entity.multipart.HttpPart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;

import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.ParseException;

/**
 * Creates multipart content from a composed {@link HttpEntity}.
 *
 * @since 1.0
 */
public class HttpMultipartEncoder {

  private static final String FORM_DATA = "form-data";
  public static final String ATTACHMENT = "attachment";

  public static MimeMultipart toMimeMultipart(HttpEntity body, String contentType) throws IOException {
    String contentTypeSubType = getContentTypeSubType(contentType);
    MimeMultipart mimeMultipartContent = new HttpMimeMultipart(contentType, contentTypeSubType);
    final Collection<HttpPart> parts = body.getParts();

    for (HttpPart part : parts) {
      final InternetHeaders internetHeaders = new InternetHeaders();
      for (String headerName : part.getHeaderNames()) {
        final Collection<String> headerValues = part.getHeaders(headerName);
        for (String headerValue : headerValues) {
          internetHeaders.addHeader(headerName, headerValue);
        }
      }
      if (internetHeaders.getHeader(CONTENT_DISPOSITION) == null) {
        String partType = contentTypeSubType.equals(FORM_DATA) ? FORM_DATA : ATTACHMENT;
        internetHeaders.addHeader(CONTENT_DISPOSITION, getContentDisposition(part, partType));
      }
      if (internetHeaders.getHeader(CONTENT_TYPE) == null && part.getContentType() != null) {
        internetHeaders.addHeader(CONTENT_TYPE, part.getContentType());
      }
      try {
        // TODO: MULE-12827 - Support HTTP multipart streaming
        final byte[] partContent = IOUtils.toByteArray(part.getInputStream());
        mimeMultipartContent.addBodyPart(new MimeBodyPart(internetHeaders, partContent));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return mimeMultipartContent;
  }

  public static byte[] toByteArray(HttpEntity multipartEntity, String contentType) throws IOException {
    MimeMultipart mimeMultipartContent = HttpMultipartEncoder.toMimeMultipart(multipartEntity, contentType);
    final ByteArrayOutputStream byteArrayOutputStream;
    byteArrayOutputStream = new ByteArrayOutputStream();
    try {
      mimeMultipartContent.writeTo(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    } catch (MessagingException e) {
      throw new IOException(e);
    }
  }

  /**
   * Extracts the subtype from a content type
   *
   * @param contentType the content type
   * @return subtype of the content type.
   */
  private static String getContentTypeSubType(String contentType) {
    final ContentType contentTypeValue;
    try {
      contentTypeValue = new ContentType(contentType);
      return contentTypeValue.getSubType();
    } catch (ParseException e) {
      throw new MuleRuntimeException(e);
    }
  }

  private static String getContentDisposition(HttpPart part, String partType) {
    StringBuilder builder = new StringBuilder();
    builder.append(partType);
    builder.append("; name=\"");
    builder.append(part.getName());
    builder.append("\"");
    if (part.getFileName() != null) {
      builder.append("; filename=\"");
      builder.append(part.getFileName());
      builder.append("\"");
    }
    return builder.toString();
  }
}
