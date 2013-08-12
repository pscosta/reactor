/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.tcp.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Environment;
import reactor.core.Reactor;
import reactor.core.composable.Deferred;
import reactor.core.composable.Promise;
import reactor.core.composable.spec.Promises;
import reactor.function.Supplier;
import reactor.io.Buffer;
import reactor.support.NamedDaemonThreadFactory;
import reactor.tcp.Reconnect;
import reactor.tcp.TcpClient;
import reactor.tcp.TcpConnection;
import reactor.tcp.config.ClientSocketOptions;
import reactor.tcp.encoding.Codec;
import reactor.tuple.Tuple2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Netty-based {@code TcpClient}.
 *
 * @param <IN>  The type that will be received by this client
 * @param <OUT> The type that will be sent by this client
 * @author Jon Brisbin
 */
public class NettyTcpClient<IN, OUT> extends TcpClient<IN, OUT> {

	private final Logger        log                = LoggerFactory.getLogger(NettyTcpClient.class);
	private final AtomicInteger connectionAttempts = new AtomicInteger(0);

	private final    Bootstrap                                                         bootstrap;
	private final    Reactor                                                           eventsReactor;
	private final    ClientSocketOptions                                               options;
	private final    EventLoopGroup                                                    ioGroup;
	private final    Supplier<ChannelFuture>                                           connectionSupplier;
	private volatile Reconnect                                                         reconnect;
	private volatile Deferred<TcpConnection<IN, OUT>, Promise<TcpConnection<IN, OUT>>> connection;
	private volatile InetSocketAddress                                                 connectAddress;

	/**
	 * Creates a new NettyTcpClient that will use the given {@code env} for configuration and the given {@code reactor} to
	 * send events. The number of IO threads used by the client is configured by the environment's {@code
	 * reactor.tcp.ioThreadCount} property. In its absence the number of IO threads will be equal to the {@link
	 * Environment#PROCESSORS number of available processors}. </p> The client will connect to the given {@code
	 * connectAddress}, configuring its socket using the given {@code opts}. The given {@code codec} will be used for
	 * encoding and decoding of data.
	 *
	 * @param env            The configuration environment
	 * @param reactor        The reactor used to send events
	 * @param connectAddress The address the client will connect to
	 * @param opts           The configuration options for the client's socket
	 * @param codec          The codec used to encode and decode data
	 */
	public NettyTcpClient(@Nonnull Environment env,
												@Nonnull Reactor reactor,
												@Nonnull InetSocketAddress connectAddress,
												@Nonnull ClientSocketOptions opts,
												@Nullable Reconnect reconnect,
												@Nullable Codec<Buffer, IN, OUT> codec) {
		super(env, reactor, connectAddress, codec);
		this.eventsReactor = reactor;
		this.connectAddress = connectAddress;
		this.options = opts;
		this.reconnect = reconnect;

		this.connection = Promises.<TcpConnection<IN, OUT>>defer()
															.env(env)
															.dispatcher(eventsReactor.getDispatcher())
															.get();

		int ioThreadCount = env.getProperty("reactor.tcp.ioThreadCount", Integer.class, Environment.PROCESSORS);
		ioGroup = new NioEventLoopGroup(ioThreadCount, new NamedDaemonThreadFactory("reactor-tcp-io"));

		this.bootstrap = new Bootstrap()
				.group(ioGroup)
				.channel(NioSocketChannel.class)
				.option(ChannelOption.SO_RCVBUF, options.rcvbuf())
				.option(ChannelOption.SO_SNDBUF, options.sndbuf())
				.option(ChannelOption.SO_KEEPALIVE, options.keepAlive())
				.option(ChannelOption.SO_LINGER, options.linger())
				.option(ChannelOption.TCP_NODELAY, options.tcpNoDelay())
				.remoteAddress(connectAddress)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					public void initChannel(final SocketChannel ch) throws Exception {
						if (!connection.compose().isComplete()) {
							ch.config().setConnectTimeoutMillis(options.timeout());
							ch.pipeline().addLast(createChannelHandlers(ch));
						}
					}
				});

		this.connectionSupplier = new Supplier<ChannelFuture>() {
			@Override
			public ChannelFuture get() {
				return bootstrap.connect(NettyTcpClient.this.connectAddress);
			}
		};
	}

	@Override
	public Promise<TcpConnection<IN, OUT>> open() {
		connectionSupplier.get().addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				if (future.isSuccess()) {
					if (log.isInfoEnabled()) {
						log.info("CONNECT: " + future.channel());
					}

					future.channel().closeFuture().addListener(new ChannelFutureListener() {
						@Override
						public void operationComplete(ChannelFuture future) throws Exception {
							if (log.isInfoEnabled()) {
								log.info("CLOSED: " + future.channel());
							}
							if (null != reconnect) {
								open();
							}
						}
					});

					connectionAttempts.set(0);
					NettyTcpConnection<IN, OUT> conn = (NettyTcpConnection<IN, OUT>) select(future.channel());
					if (!connection.compose().isComplete()) {
						connection.accept(conn);
					} else {
						if (null != reconnect) {
							reconnect.reconnected();
							conn.reconnected((SocketChannel) future.channel(), connectAddress);
						}
					}
				} else {
					reconnect(future);
				}
			}
		});

		return connection.compose();
	}

	@Override
	protected <C> TcpConnection<IN, OUT> createConnection(C channel) {
		SocketChannel ch = (SocketChannel) channel;
		int backlog = env.getProperty("reactor.tcp.connectionReactorBacklog", Integer.class, 128);

		return new NettyTcpConnection<IN, OUT>(
				env,
				getCodec(),
				new NettyEventLoopDispatcher(ch.eventLoop(), backlog),
				eventsReactor,
				ch,
				connectAddress
		);
	}

	protected ChannelHandler[] createChannelHandlers(SocketChannel ch) {
		NettyTcpConnection<IN, OUT> conn = (NettyTcpConnection<IN, OUT>) select(ch);
		return new ChannelHandler[]{
				new NettyTcpConnectionChannelInboundHandler(conn) {
					@Override
					public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
						NettyTcpClient.this.notifyError(cause);
					}
				}
		};
	}

	@Override
	public Promise<Void> close() {
		reconnect = null;
		return super.close();
	}

	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	protected void doClose(final Deferred<Void, Promise<Void>> d) {
		try {
			this.ioGroup.shutdownGracefully().await().addListener(new GenericFutureListener() {
				@Override
				public void operationComplete(Future future) throws Exception {
					// Sleep for 1 second to allow Netty's GlobalEventExecutor thread to die
					// TODO We need a better way of being sure that all of Netty's threads have died
					Thread.sleep(1000);
					d.accept((Void) null);
				}
			});
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			d.accept(e);
		}
	}

	private void reconnect(ChannelFuture future) {
		if (null == reconnect) {
			return;
		}
		Tuple2<InetSocketAddress, Long> tup = reconnect.reconnect(connectAddress, connectionAttempts.incrementAndGet());
		if (null != tup) {
			if (log.isInfoEnabled()) {
				log.info("Attempt " + connectionAttempts.get() + " to connect to " + connectAddress);
			}
			connectAddress = tup.getT1();
			env.getRootTimer().schedule(
					new TimerTask() {
						@Override
						public void run() {
							open();
						}
					},
					tup.getT2()
			);
		} else {
			if (!connection.compose().isComplete()) {
				connection.accept(future.cause());
			}
			if (log.isErrorEnabled()) {
				log.error("Reconnection to " + connectAddress + " failed after " + (connectionAttempts.get() - 1) + " attempts.");
			}
		}
	}

}
