/*
 * Copyright (c) 2011-2025 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.tracing.opentracing;

import io.jaegertracing.Configuration;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.vertx.core.Context;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.tracing.TracingPolicy;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

import static io.vertx.core.spi.context.storage.AccessMode.CONCURRENT;
import static io.vertx.tracing.opentracing.OpenTracingTracerFactory.ACTIVE_SPAN;

/**
 * - https://github.com/opentracing/specification/blob/master/semantic_conventions.md
 * - https://github.com/opentracing/specification/blob/master/specification.md
 */
public class OpenTracingTracer implements io.vertx.core.spi.tracing.VertxTracer<Span, Span> {

  /**
   * Instantiate an OpenTracing tracer configured from ENV, e.g {@code JAEGER_SERVICE_NAME}, etc...
   */
  static Tracer createDefaultTracer() {
    Configuration config = Configuration.fromEnv();
    return config.getTracerBuilder().build();
  }

  private final boolean closeTracer;
  private final Tracer tracer;

  /**
   * Instantiate a OpenTracing tracer using the specified {@code tracer}.
   *
   * @param closeTracer close the tracer when necessary
   * @param tracer the tracer instance
   */
  public OpenTracingTracer(boolean closeTracer, Tracer tracer) {
    this.closeTracer = closeTracer;
    this.tracer = tracer;
  }

  @Override
  public <R> Span receiveRequest(Context context,
                                 SpanKind kind,
                                 TracingPolicy policy,
                                 R request,
                                 String operation,
                                 Iterable<Map.Entry<String, String>> headers,
                                 TagExtractor<R> tagExtractor) {
    if (policy != TracingPolicy.IGNORE) {
      SpanContext sc = tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMap() {
        @Override
        public Iterator<Map.Entry<String, String>> iterator() {
          return headers.iterator();
        }

        @Override
        public void put(String key, String value) {
          throw new UnsupportedOperationException();
        }
      });
      if (sc != null || policy == TracingPolicy.ALWAYS) {
        Span span = tracer.buildSpan(operation)
          .ignoreActiveSpan()
          .asChildOf(sc)
          .withTag(Tags.SPAN_KIND.getKey(), kind == SpanKind.RPC ? Tags.SPAN_KIND_SERVER : Tags.SPAN_KIND_CONSUMER)
          .withTag(Tags.COMPONENT.getKey(), "vertx")
          .start();
        reportTags(span, request, tagExtractor);
        ((ContextInternal) context).putLocal(ACTIVE_SPAN, CONCURRENT, span);
        return span;
      }
    }
    return null;
  }

  @Override
  public <R> void sendResponse(
    Context context, R response, Span span, Throwable failure, TagExtractor<R> tagExtractor) {
    if (span != null) {
      ((ContextInternal) context).removeLocal(ACTIVE_SPAN, CONCURRENT);
      if (failure != null) {
        reportFailure(span, failure);
      }
      if (response != null) {
        reportTags(span, response, tagExtractor);
      }
      span.finish();
    }
  }

  @Override
  public <R> Span sendRequest(Context context,
                              SpanKind kind,
                              TracingPolicy policy,
                              R request,
                              String operation,
                              BiConsumer<String, String> headers,
                              TagExtractor<R> tagExtractor) {
    if (policy == TracingPolicy.IGNORE) {
      return null;
    }
    Span active = ((ContextInternal)context).getLocal(ACTIVE_SPAN);
    if (active != null || policy == TracingPolicy.ALWAYS) {
      Span span = tracer
        .buildSpan(operation)
        .asChildOf(active)
        .withTag(Tags.SPAN_KIND.getKey(), kind == SpanKind.RPC ? Tags.SPAN_KIND_CLIENT : Tags.SPAN_KIND_PRODUCER)
        .withTag(Tags.COMPONENT.getKey(), "vertx")
        .start();
      reportTags(span, request, tagExtractor);
      if (headers != null) {
        tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new TextMap() {
          @Override
          public Iterator<Map.Entry<String, String>> iterator() {
            throw new UnsupportedOperationException();
          }

          @Override
          public void put(String key, String value) {
            headers.accept(key, value);
          }
        });
      }
      return span;
    }
    return null;
  }

  @Override
  public <R> void receiveResponse(Context context, R response, Span span, Throwable failure,
    TagExtractor<R> tagExtractor) {
    if (span != null) {
      if (failure != null) {
        reportFailure(span, failure);
      }
      if (response != null) {
        reportTags(span, response, tagExtractor);
      }
      span.finish();
    }
  }

  private <T> void reportTags(Span span, T obj, TagExtractor<T> tagExtractor) {
    int len = tagExtractor.len(obj);
    for (int idx = 0; idx < len; idx++) {
      String name = tagExtractor.name(obj, idx);
      String value = tagExtractor.value(obj, idx);
      switch (name) {
        case "server.address":
        case "network.peer.address":
          span.setTag("peer.address", value);
          break;
        case "server.port":
        case "network.peer.port":
          span.setTag("peer.port", value);
          break;
        case "messaging.destination.name":
          span.setTag("message_bus.destination", value);
          break;
        case "messaging.system":
          span.setTag("message_bus.system", value);
          break;
        case "messaging.operation":
          span.setTag("message_bus.operation", value);
          break;
        case "db.namespace":
          span.setTag("db.instance", value);
          break;
        case "db.query.text":
        case "db.operation.name":
          span.setTag("db.statement", value);
          break;
        case "db.system":
          span.setTag("db.type", value);
          break;
        case "http.request.method":
          span.setTag("http.method", value);
          break;
        case "http.response.status_code":
          span.setTag("http.status_code", value);
          break;
        case "url.full":
          span.setTag("http.url", value);
          break;
        case "url.scheme":
          span.setTag("http.scheme", value);
          break;
        case "url.path":
          span.setTag("http.path", value);
          break;
        case "url.query":
          span.setTag("http.query", value);
          break;
        default:
          span.setTag(name, value);
      }
    }
  }

  private void reportFailure(Span span, Throwable failure) {
    if (failure != null) {
      span.setTag("error", true);
      HashMap<String, Object> fields = new HashMap<>();
      fields.put(Fields.EVENT, "error");
      fields.put(Fields.MESSAGE, failure.getMessage());
      fields.put(Fields.ERROR_KIND, "Exception");
      fields.put(Fields.ERROR_OBJECT, failure);
      span.log(fields);
    }
  }

  @Override
  public void close() {
    if (closeTracer && tracer != null) {
      tracer.close();
    }
  }
}
