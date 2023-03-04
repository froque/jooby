/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.jooby.annotation.Transactional;
import io.jooby.exception.MethodNotAllowedException;
import io.jooby.exception.NotAcceptableException;
import io.jooby.exception.NotFoundException;
import io.jooby.exception.StatusCodeException;
import io.jooby.exception.UnsupportedMediaType;

/**
 * Route contains information about the HTTP method, path pattern, which content types consumes and
 * produces, etc..
 *
 * <p>Additionally, contains metadata about route return Java type, argument source (query, path,
 * etc..) and Java type.
 *
 * <p>This class contains all the metadata associated to a route. It is like a {@link Class} object
 * for routes.
 *
 * @author edgar
 * @since 2.0.0
 */
public class Route {

  /**
   * Decorates a route handler by running logic before and after route handler.
   *
   * <pre>{@code
   * {
   *   use(next -> ctx -> {
   *     long start = System.currentTimeMillis();
   *     Object result = next.apply(ctx);
   *     long end = System.currentTimeMillis();
   *     System.out.println("Took: " + (end - start));
   *     return result;
   *   });
   * }
   * }</pre>
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Filter extends Aware {
    /**
     * Chain the filter within next handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @NonNull Handler apply(@NonNull Handler next);

    /**
     * Chain this filter with another and produces a new decorator.
     *
     * @param next Next decorator.
     * @return A new decorator.
     */
    @NonNull default Filter then(@NonNull Filter next) {
      return h -> apply(next.apply(h));
    }

    /**
     * Chain this filter with a handler and produces a new handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @NonNull default Handler then(@NonNull Handler next) {
      return ctx -> apply(next).apply(ctx);
    }
  }

  /**
   * @deprecated use {@link Route.Filter}.
   */
  @Deprecated
  public interface Decorator extends Filter {}

  /**
   * Decorates a handler and run logic before handler is executed.
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Before extends Filter {

    default @NonNull @Override Handler apply(@NonNull Handler next) {
      return ctx -> {
        apply(ctx);
        return next.apply(ctx);
      };
    }

    /**
     * Execute application code before next handler.
     *
     * @param ctx Web context.
     * @throws Exception If something goes wrong.
     */
    void apply(@NonNull Context ctx) throws Exception;

    /**
     * Chain this filter with next one and produces a new before filter.
     *
     * @param next Next decorator.
     * @return A new decorator.
     */
    @NonNull default Before then(@NonNull Before next) {
      return ctx -> {
        apply(ctx);
        if (!ctx.isResponseStarted()) {
          next.apply(ctx);
        }
      };
    }

    /**
     * Chain this decorator with a handler and produces a new handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @NonNull default Handler then(@NonNull Handler next) {
      return ctx -> {
        apply(ctx);
        if (!ctx.isResponseStarted()) {
          return next.apply(ctx);
        }
        return ctx;
      };
    }
  }

  /**
   * Execute application logic after a response has been generated by a route handler.
   *
   * <p>For functional handler the value is accessible and you are able to modify the response:
   *
   * <pre>{@code
   * {
   *   after((ctx, result) -> {
   *     // Modify response
   *     ctx.setResponseHeader("foo", "bar");
   *     // do something with value:
   *     log.info("{} produces {}", ctx, result);
   *   });
   *
   *   get("/", ctx -> {
   *     return "Functional value";
   *   });
   * }
   * }</pre>
   *
   * For side-effect handler (direct use of send methods, outputstream, writer, etc.) you are not
   * allowed to modify the response or access to the value (value is always <code>null</code>):
   *
   * <pre>{@code
   * {
   *   after((ctx, result) -> {
   *     // Always null:
   *     assertNull(result);
   *
   *     // Response started is set to: true
   *     assertTrue(ctx.isResponseStarted());
   *   });
   *
   *   get("/", ctx -> {
   *     return ctx.send("Side effect");
   *   });
   * }
   * }</pre>
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface After {

    /**
     * Chain this filter with next one and produces a new after filter.
     *
     * @param next Next filter.
     * @return A new filter.
     */
    @NonNull default After then(@NonNull After next) {
      return (ctx, result, failure) -> {
        next.apply(ctx, result, failure);
        apply(ctx, result, failure);
      };
    }

    /**
     * Execute application logic on a route response.
     *
     * @param ctx Web context.
     * @param result Response generated by route handler.
     * @param failure Uncaught exception generated by route handler.
     * @throws Exception If something goes wrong.
     */
    void apply(@NonNull Context ctx, @Nullable Object result, @Nullable Throwable failure)
        throws Exception;
  }

  /**
   * Listener interface for events that are run at the completion of a request/response cycle (i.e.
   * when the request has been completely read, and the response has been fully written).
   *
   * <p>At this point it is too late to modify the exchange further.
   *
   * <p>Completion listeners are invoked in reverse order.
   *
   * @author edgar
   */
  public interface Complete {
    /**
     * Callback method.
     *
     * @param ctx Read-Only web context.
     * @throws Exception If something goes wrong.
     */
    void apply(@NonNull Context ctx) throws Exception;
  }

  public interface Aware {
    /**
     * Allows a handler to listen for route metadata.
     *
     * @param route Route metadata.
     */
    default void setRoute(Route route) {}
  }

  /**
   * Route handler here is where the application logic lives.
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Handler extends Serializable, Aware {

    /**
     * Execute application code.
     *
     * @param ctx Web context.
     * @return Route response.
     * @throws Exception If something goes wrong.
     */
    @NonNull Object apply(@NonNull Context ctx) throws Exception;

    /**
     * Chain this after decorator with next and produces a new decorator.
     *
     * @param next Next decorator.
     * @return A new handler.
     */
    @NonNull default Handler then(@NonNull After next) {
      return ctx -> {
        Throwable cause = null;
        Object value = null;
        try {
          value = apply(ctx);
        } catch (Throwable x) {
          cause = x;
          // Early mark context as errored response code:
          ctx.setResponseCode(ctx.getRouter().errorCode(cause));
        }
        Object result;
        try {
          if (ctx.isResponseStarted()) {
            result = Context.readOnly(ctx);
            next.apply((Context) result, value, cause);
          } else {
            result = value;
            next.apply(ctx, value, cause);
          }
        } catch (Throwable x) {
          result = null;
          if (cause == null) {
            cause = x;
          } else {
            cause.addSuppressed(x);
          }
        }

        if (cause == null) {
          return result;
        } else {
          if (ctx.isResponseStarted()) {
            return ctx;
          } else {
            throw SneakyThrows.propagate(cause);
          }
        }
      };
    }
  }

  /** Handler for {@link StatusCode#NOT_FOUND} responses. */
  public static final Handler NOT_FOUND =
      ctx -> ctx.sendError(new NotFoundException(ctx.getRequestPath()));

  /** Handler for {@link StatusCode#METHOD_NOT_ALLOWED} responses. */
  public static final Handler METHOD_NOT_ALLOWED =
      ctx -> {
        ctx.setResetHeadersOnError(false);
        // Allow header was generated by routing algorithm
        if (ctx.getMethod().equals(Router.OPTIONS)) {
          return ctx.send(StatusCode.OK);
        } else {
          List<String> allow =
              Optional.ofNullable(ctx.getResponseHeader("Allow"))
                  .map(it -> Arrays.asList(it.split(",")))
                  .orElseGet(Collections::emptyList);
          return ctx.sendError(new MethodNotAllowedException(ctx.getMethod(), allow));
        }
      };

  /** Handler for {@link StatusCode#REQUEST_ENTITY_TOO_LARGE} responses. */
  public static final Handler REQUEST_ENTITY_TOO_LARGE =
      ctx -> ctx.sendError(new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE));

  /** Handler for {@link StatusCode#NOT_ACCEPTABLE} responses. */
  public static final Route.Before ACCEPT =
      ctx -> {
        List<MediaType> produceTypes = ctx.getRoute().getProduces();
        MediaType contentType = ctx.accept(produceTypes);
        if (contentType == null) {
          throw new NotAcceptableException(ctx.header(Context.ACCEPT).valueOrNull());
        }
        ctx.setDefaultResponseType(contentType);
      };

  /** Handler for {@link StatusCode#UNSUPPORTED_MEDIA_TYPE} responses. */
  public static final Route.Before SUPPORT_MEDIA_TYPE =
      ctx -> {
        if (!ctx.isPreflight()) {
          MediaType contentType = ctx.getRequestType();
          if (contentType == null) {
            throw new UnsupportedMediaType(null);
          }
          if (!ctx.getRoute().getConsumes().stream().anyMatch(contentType::matches)) {
            throw new UnsupportedMediaType(contentType.getValue());
          }
        }
      };

  /** Favicon handler as a silent 404 error. */
  public static final Handler FAVICON = ctx -> ctx.send(StatusCode.NOT_FOUND);

  private static final List EMPTY_LIST = Collections.emptyList();

  private static final Map EMPTY_MAP = Collections.emptyMap();

  private Map<String, MessageDecoder> decoders = EMPTY_MAP;

  private final String pattern;

  private final String method;

  private List<String> pathKeys = EMPTY_LIST;

  private Filter filter;

  private Handler handler;

  private After after;

  private Handler pipeline;

  private MessageEncoder encoder;

  private Type returnType;

  private Object handle;

  private List<MediaType> produces = EMPTY_LIST;

  private List<MediaType> consumes = EMPTY_LIST;

  private Map<String, Object> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  private Set<String> supportedMethod;

  private String executorKey;

  private List<String> tags = EMPTY_LIST;

  private String summary;

  private String description;

  private boolean reactive;

  /**
   * Creates a new route.
   *
   * @param method HTTP method.
   * @param pattern Path pattern.
   * @param handler Route handler.
   */
  public Route(@NonNull String method, @NonNull String pattern, @NonNull Handler handler) {
    this.method = method.toUpperCase();
    this.pattern = pattern;
    this.handler = handler;
    this.handle = handler;
  }

  /**
   * Path pattern.
   *
   * @return Path pattern.
   */
  public @NonNull String getPattern() {
    return pattern;
  }

  /**
   * HTTP method.
   *
   * @return HTTP method.
   */
  public @NonNull String getMethod() {
    return method;
  }

  /**
   * Path keys.
   *
   * @return Path keys.
   */
  public @NonNull List<String> getPathKeys() {
    return pathKeys;
  }

  /**
   * Set path keys.
   *
   * @param pathKeys Path keys or empty list.
   * @return This route.
   */
  public @NonNull Route setPathKeys(@NonNull List<String> pathKeys) {
    this.pathKeys = pathKeys;
    return this;
  }

  /**
   * Route handler.
   *
   * @return Route handler.
   */
  public @NonNull Handler getHandler() {
    return handler;
  }

  /**
   * Route pipeline.
   *
   * @return Route pipeline.
   */
  public @NonNull Handler getPipeline() {
    if (pipeline == null) {
      pipeline = computePipeline();
    }
    return pipeline;
  }

  /**
   * Recreate a path pattern using the given variables. <code>
   * reserve(/{k1}/{k2}, {"k1": ""foo", "k2": "bar"}) =&gt; /foo/bar</code>
   *
   * @param keys Path keys.
   * @return Path.
   */
  public @NonNull String reverse(@NonNull Map<String, Object> keys) {
    return Router.reverse(getPattern(), keys);
  }

  /**
   * Recreate a path pattern using the given variables. <code>
   * reserve(/{k1}/{k2}, "foo", "bar") =&gt; /foo/bar</code>
   *
   * @param values Values.
   * @return Path.
   */
  public @NonNull String reverse(Object... values) {
    return Router.reverse(getPattern(), values);
  }

  /**
   * Handler instance which might or might not be the same as {@link #getHandler()}.
   *
   * <p>The handle is required to extract correct metadata.
   *
   * @return Handle.
   */
  public @NonNull Object getHandle() {
    return handle;
  }

  /**
   * After filter or <code>null</code>.
   *
   * @return After filter or <code>null</code>.
   */
  public @Nullable After getAfter() {
    return after;
  }

  /**
   * Set after filter.
   *
   * @param after After filter.
   * @return This route.
   */
  public @NonNull Route setAfter(@NonNull After after) {
    this.after = after;
    return this;
  }

  /**
   * Decorator or <code>null</code>.
   *
   * @return Decorator or <code>null</code>.
   */
  public @Nullable Filter getFilter() {
    return filter;
  }

  /**
   * Set route filter.
   *
   * @param filter Filter.
   * @return This route.
   */
  public @NonNull Route setFilter(@Nullable Filter filter) {
    this.filter = filter;
    return this;
  }

  /**
   * Set route handle instance, required when handle is different from {@link #getHandler()}.
   *
   * @param handle Handle instance.
   * @return This route.
   */
  public @NonNull Route setHandle(@NonNull Object handle) {
    this.handle = handle;
    return this;
  }

  /**
   * Set route pipeline. This method is part of public API but isn't intended to be used by public.
   *
   * @param pipeline Pipeline.
   * @return This routes.
   */
  public @NonNull Route setPipeline(Route.Handler pipeline) {
    this.pipeline = pipeline;
    return this;
  }

  /**
   * Route encoder.
   *
   * @return Route encoder.
   */
  public @NonNull MessageEncoder getEncoder() {
    return encoder;
  }

  /**
   * Set encoder.
   *
   * @param encoder MessageEncoder.
   * @return This route.
   */
  public @NonNull Route setEncoder(@NonNull MessageEncoder encoder) {
    this.encoder = encoder;
    return this;
  }

  public boolean isReactive() {
    return reactive;
  }

  public @NonNull Route setReactive(boolean reactive) {
    this.reactive = reactive;
    return this;
  }

  /**
   * Return return type.
   *
   * @return Return type.
   */
  public @Nullable Type getReturnType() {
    return returnType;
  }

  /**
   * Set route return type.
   *
   * @param returnType Return type.
   * @return This route.
   */
  public @NonNull Route setReturnType(@Nullable Type returnType) {
    this.returnType = returnType;
    return this;
  }

  /**
   * Response types (format) produces by this route. If set, we expect to find a match in the <code>
   * Accept</code> header. If none matches, we send a {@link StatusCode#NOT_ACCEPTABLE} response.
   *
   * @return Immutable list of produce types.
   */
  public @NonNull List<MediaType> getProduces() {
    return produces;
  }

  /**
   * Add one or more response types (format) produces by this route.
   *
   * @param produces Produce types.
   * @return This route.
   */
  public @NonNull Route produces(@NonNull MediaType... produces) {
    return setProduces(Arrays.asList(produces));
  }

  /**
   * Add one or more response types (format) produces by this route.
   *
   * @param produces Produce types.
   * @return This route.
   */
  public @NonNull Route setProduces(@NonNull Collection<MediaType> produces) {
    if (produces.size() > 0) {
      if (this.produces == EMPTY_LIST) {
        this.produces = new ArrayList<>();
      }
      produces.forEach(this.produces::add);
    }
    return this;
  }

  /**
   * Request types (format) consumed by this route. If set the <code>Content-Type</code> header is
   * checked against these values. If none matches we send a {@link
   * StatusCode#UNSUPPORTED_MEDIA_TYPE} exception.
   *
   * @return Immutable list of consumed types.
   */
  public @NonNull List<MediaType> getConsumes() {
    return consumes;
  }

  /**
   * Add one or more request types (format) consumed by this route.
   *
   * @param consumes Consume types.
   * @return This route.
   */
  public @NonNull Route consumes(@NonNull MediaType... consumes) {
    return setConsumes(Arrays.asList(consumes));
  }

  /**
   * Add one or more request types (format) consumed by this route.
   *
   * @param consumes Consume types.
   * @return This route.
   */
  public @NonNull Route setConsumes(@NonNull Collection<MediaType> consumes) {
    if (consumes.size() > 0) {
      if (this.consumes == EMPTY_LIST) {
        this.consumes = new ArrayList<>();
      }
      consumes.forEach(this.consumes::add);
    }
    return this;
  }

  /**
   * Attributes set to this route.
   *
   * @return Map of attributes set to the route.
   */
  public @NonNull Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Retrieve value of this specific Attribute set to this route.
   *
   * @param name of the attribute to retrieve.
   * @param <T> Generic type.
   * @return value of the specific attribute.
   */
  public @Nullable <T> T attribute(@NonNull String name) {
    return (T) attributes.get(name);
  }

  /**
   * Add one or more attributes applied to this route.
   *
   * @param attributes .
   * @return This route.
   */
  public @NonNull Route setAttributes(@NonNull Map<String, Object> attributes) {
    this.attributes.putAll(attributes);
    return this;
  }

  /**
   * Add one or more attributes applied to this route.
   *
   * @param name attribute name
   * @param value attribute value
   * @return This route.
   */
  public @NonNull Route attribute(@NonNull String name, @NonNull Object value) {
    if (this.attributes == EMPTY_MAP) {
      this.attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    this.attributes.put(name, value);

    return this;
  }

  /**
   * MessageDecoder for given media type.
   *
   * @param contentType Media type.
   * @return MessageDecoder.
   */
  public @NonNull MessageDecoder decoder(@NonNull MediaType contentType) {
    return decoders.getOrDefault(contentType.getValue(), MessageDecoder.UNSUPPORTED_MEDIA_TYPE);
  }

  /**
   * Route message decoder.
   *
   * @return Message decoders.
   */
  public @NonNull Map<String, MessageDecoder> getDecoders() {
    return decoders;
  }

  /**
   * Set message decoders. Map key is a mime-type.
   *
   * @param decoders message decoder.
   * @return This route.
   */
  public @NonNull Route setDecoders(@NonNull Map<String, MessageDecoder> decoders) {
    this.decoders = decoders;
    return this;
  }

  /**
   * True if route support HTTP OPTIONS.
   *
   * @return True if route support HTTP OPTIONS.
   */
  public boolean isHttpOptions() {
    return isHttpMethod(Router.OPTIONS);
  }

  /**
   * True if route support HTTP TRACE.
   *
   * @return True if route support HTTP TRACE.
   */
  public boolean isHttpTrace() {
    return isHttpMethod(Router.TRACE);
  }

  /**
   * True if route support HTTP HEAD.
   *
   * @return True if route support HTTP HEAD.
   */
  public boolean isHttpHead() {
    return getMethod().equals(Router.GET) && isHttpMethod(Router.HEAD);
  }

  /**
   * Enabled or disabled HTTP Options.
   *
   * @param enabled Enabled or disabled HTTP Options.
   * @return This route.
   */
  public @NonNull Route setHttpOptions(boolean enabled) {
    addHttpMethod(enabled, Router.OPTIONS);
    return this;
  }

  /**
   * Enabled or disabled HTTP TRACE.
   *
   * @param enabled Enabled or disabled HTTP TRACE.
   * @return This route.
   */
  public @NonNull Route setHttpTrace(boolean enabled) {
    addHttpMethod(enabled, Router.TRACE);
    return this;
  }

  /**
   * Enabled or disabled HTTP HEAD.
   *
   * @param enabled Enabled or disabled HTTP HEAD.
   * @return This route.
   */
  public @NonNull Route setHttpHead(boolean enabled) {
    addHttpMethod(enabled, Router.HEAD);
    return this;
  }

  /**
   * Specify the name of the executor where the route is going to run. Default is <code>null</code>.
   *
   * @return Executor key.
   */
  public @Nullable String getExecutorKey() {
    return executorKey;
  }

  /**
   * Set executor key. The route is going to use the given key to fetch an executor. Possible values
   * are:
   *
   * <p>- <code>null</code>: no specific executor, uses the default Jooby logic to choose one, based
   * on the value of {@link ExecutionMode}; - <code>worker</code>: use the executor provided by the
   * server. - <code>arbitrary name</code>: use an named executor which as registered using {@link
   * Router#executor(String, Executor)}.
   *
   * @param executorKey Executor key.
   * @return This route.
   */
  public @NonNull Route setExecutorKey(@Nullable String executorKey) {
    this.executorKey = executorKey;
    return this;
  }

  /**
   * Route tags.
   *
   * @return Route tags.
   */
  public @NonNull List<String> getTags() {
    return tags;
  }

  /**
   * Tag this route. Tags are used for documentation purpose from openAPI generator.
   *
   * @param tags Tags.
   * @return This route.
   */
  public @NonNull Route setTags(@NonNull List<String> tags) {
    if (this.tags == EMPTY_LIST) {
      this.tags = new ArrayList<>();
    }
    this.tags.addAll(tags);
    return this;
  }

  /**
   * Add a tag to this route.
   *
   * <p>Tags are used for documentation purpose from openAPI generator.
   *
   * @param tag Tag.
   * @return This route.
   */
  public @NonNull Route addTag(@NonNull String tag) {
    if (this.tags == EMPTY_LIST) {
      this.tags = new ArrayList<>();
    }
    this.tags.add(tag);
    return this;
  }

  /**
   * Tag this route. Tags are used for documentation purpose from openAPI generator.
   *
   * @param tags Tags.
   * @return This route.
   */
  public @NonNull Route tags(@NonNull String... tags) {
    return setTags(Arrays.asList(tags));
  }

  /**
   * Route summary useful for documentation purpose from openAPI generator.
   *
   * @return Summary.
   */
  public @Nullable String getSummary() {
    return summary;
  }

  /**
   * Route summary useful for documentation purpose from openAPI generator.
   *
   * @param summary Summary.
   * @return This route.
   */
  public @NonNull Route summary(@Nullable String summary) {
    return setSummary(summary);
  }

  /**
   * Route summary useful for documentation purpose from openAPI generator.
   *
   * @param summary Summary.
   * @return This route.
   */
  public @NonNull Route setSummary(@Nullable String summary) {
    this.summary = summary;
    return this;
  }

  /**
   * Route description useful for documentation purpose from openAPI generator.
   *
   * @return Route description.
   */
  public @Nullable String getDescription() {
    return description;
  }

  /**
   * Route description useful for documentation purpose from openAPI generator.
   *
   * @param description Description.
   * @return This route.
   */
  public @NonNull Route setDescription(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * Route description useful for documentation purpose from openAPI generator.
   *
   * @param description Description.
   * @return This route.
   */
  public @NonNull Route description(@Nullable String description) {
    return setDescription(description);
  }

  /**
   * Returns whether this route is marked as transactional, or returns {@code defaultValue} if this
   * route has not been marked explicitly.
   *
   * @param defaultValue the value to return if this route was not explicitly marked
   * @return whether this route should be considered as transactional
   */
  public boolean isTransactional(boolean defaultValue) {
    Object attribute = attribute(Transactional.ATTRIBUTE);

    if (attribute == null) {
      return defaultValue;
    }

    if (attribute instanceof Boolean) {
      return (Boolean) attribute;
    }

    throw new RuntimeException(
        "Invalid value for route attribute " + Transactional.ATTRIBUTE + ": " + attribute);
  }

  @Override
  public String toString() {
    return method + " " + pattern;
  }

  private boolean isHttpMethod(String httpMethod) {
    return supportedMethod != null && supportedMethod.contains(httpMethod);
  }

  private void addHttpMethod(boolean enabled, String httpMethod) {
    if (supportedMethod == null) {
      supportedMethod = new HashSet<>();
    }
    if (enabled) {
      supportedMethod.add(httpMethod);
    } else {
      supportedMethod.remove(httpMethod);
    }
  }

  private Route.Handler computePipeline() {
    Route.Handler pipeline = computeHeadPipeline();

    if (after != null) {
      pipeline = pipeline.then(after);
    }
    return pipeline;
  }

  private Route.Handler computeHeadPipeline() {
    Route.Handler pipeline = filter == null ? handler : filter.then(handler);

    return pipeline;
  }
}
