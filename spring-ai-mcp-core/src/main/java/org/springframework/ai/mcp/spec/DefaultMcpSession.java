package org.springframework.ai.mcp.spec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import org.springframework.ai.mcp.client.util.Assert;

/**
 * Implementation of the MCP client session.
 * 
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public class DefaultMcpSession implements McpSession {

	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	private final Duration requestTimeout;

	private final ObjectMapper objectMapper;

	private final McpTransport transport;

	public DefaultMcpSession(Duration requestTimeout, ObjectMapper objectMapper, McpTransport transport) {

		Assert.notNull(objectMapper, "The ObjectMapper can not be null");
		Assert.notNull(requestTimeout, "The requstTimeout can not be null");
		Assert.notNull(transport, "The transport can not be null");

		this.requestTimeout = requestTimeout;
		this.objectMapper = objectMapper;
		this.transport = transport;

		this.transport.setInboudMessageHandler(message -> {

			if ( message instanceof McpSchema.JSONRPCResponse response) {
				var sink = pendingResponses.remove(response.id());
				if (sink == null) {
					System.out.println("Unexpected response for unkown id " + response.id());
				} else {
					sink.success(response);
				}
			} else if ( message instanceof McpSchema.JSONRPCRequest request) {
				System.out.println("Client does not yet support server requests");
			} else if ( message instanceof McpSchema.JSONRPCNotification notification) {
				System.out.println("Notifications not yet supported");
			}
		});

		this.transport.setInboundErrorHandler(error -> System.out.println("Error received " + error));

		this.transport.start();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		String requestId = UUID.randomUUID().toString();

		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			try {
				// TODO: This is non-blocking, but it's actually a synchronous call,
				// perhaps there's no need to make it return Mono?
				this.transport.sendMessage(jsonrpcRequest)
						// TODO: It's most efficient to create a dedicated
						// Subscriber here
						.subscribe(v -> {
						}, e -> {
							this.pendingResponses.remove(requestId);
							sink.error(e);
						});
			} catch (Exception e) {
				sink.error(e);
			}
		}).timeout(this.requestTimeout).handle((jsonRpcResponse, s) -> {
			if (jsonRpcResponse.error() != null) {
				s.error(new McpError(jsonRpcResponse.error()));
			} else {
				if (typeRef.getType().getTypeName().equals("java.lang.Void")) {
					s.complete();
				} else {
					s.next(this.objectMapper.convertValue(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Map<String, Object> params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		try {
			this.transport.sendMessage(jsonrpcNotification);
		} catch (Exception e) {
			return Mono.error(new McpError(e));
		}
		return Mono.empty();
	}

	@Override
	public Mono<Void> closeGracefully(Duration timeout) {
		// TODO handle the timeout in transport
		return Mono.fromRunnable(this.transport::close);
	}

}
