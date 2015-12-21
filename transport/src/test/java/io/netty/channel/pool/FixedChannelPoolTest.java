/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.pool.FixedChannelPool.AcquireTimeoutAction;
import io.netty.util.concurrent.Future;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class FixedChannelPoolTest {
    private static final String LOCAL_ADDR_ID = "test.id";

    @Test
    public void testAcquire() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        CountingChannelPoolHandler handler = new CountingChannelPoolHandler();

        ChannelPool pool = new FixedChannelPool(cb, handler, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        assertFalse(future.isDone());

        pool.release(channel).syncUninterruptibly();
        assertTrue(future.await(1, TimeUnit.SECONDS));

        Channel channel2 = future.getNow();
        assertSame(channel, channel2);
        assertEquals(1, handler.channelCount());

        assertEquals(1, handler.acquiredCount());
        assertEquals(1, handler.releasedCount());

        sc.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    @Test(expected = TimeoutException.class)
    public void testAcquireTimeout() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, ChannelHealthChecker.ACTIVE,
                                                 AcquireTimeoutAction.FAIL, 500, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        try {
            future.syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test
    public void testAcquireNewConnection() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, ChannelHealthChecker.ACTIVE,
                AcquireTimeoutAction.NEW, 500, 1, Integer.MAX_VALUE);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Channel channel2 = pool.acquire().syncUninterruptibly().getNow();
        assertNotSame(channel, channel2);
        sc.close().syncUninterruptibly();
        channel.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    /**
     * Tests that the acquiredChannelCount is not added up several times for the same channel acquire request.
     * @throws Exception
     */
    @Test
    public void testAcquireNewConnectionWhen() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1);
        Channel channel1 = pool.acquire().syncUninterruptibly().getNow();
        channel1.close().syncUninterruptibly();
        pool.release(channel1);

        Channel channel2 = pool.acquire().syncUninterruptibly().getNow();

        assertNotSame(channel1, channel2);
        sc.close().syncUninterruptibly();
        channel2.close().syncUninterruptibly();
        group.shutdownGracefully();
    }

    @Test(expected = IllegalStateException.class)
    public void testAcquireBoundQueue() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1, 1);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();
        Future<Channel> future = pool.acquire();
        assertFalse(future.isDone());

        try {
            pool.acquire().syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testReleaseDifferentPool() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup();
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
          .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
          .channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
              @Override
              public void initChannel(LocalChannel ch) throws Exception {
                  ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
              }
          });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        ChannelPoolHandler handler = new TestChannelPoolHandler();
        ChannelPool pool = new FixedChannelPool(cb, handler, 1, 1);
        ChannelPool pool2 = new FixedChannelPool(cb, handler, 1, 1);

        Channel channel = pool.acquire().syncUninterruptibly().getNow();

        try {
            pool2.release(channel).syncUninterruptibly();
        } finally {
            sc.close().syncUninterruptibly();
            channel.close().syncUninterruptibly();
            group.shutdownGracefully();
        }
    }

    @Test
    public void testReleaseAfterClosePool() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup(1);
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group).channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    public void initChannel(LocalChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                    }
                });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();

        FixedChannelPool pool = new FixedChannelPool(cb, new TestChannelPoolHandler(), 2);
        final Future<Channel> acquire = pool.acquire();
        final Channel channel = acquire.get();
        pool.close();
        group.submit(new Runnable() {
            @Override
            public void run() {
                // NOOP
            }
        }).syncUninterruptibly();
        pool.release(channel).syncUninterruptibly();
        sc.close().syncUninterruptibly();
        channel.close().syncUninterruptibly();
    }

    @Test
    public void testAcquireReleaseMany() throws Exception {
        EventLoopGroup group = new LocalEventLoopGroup(8);
        LocalAddress addr = new LocalAddress(LOCAL_ADDR_ID);
        Bootstrap cb = new Bootstrap();
        cb.remoteAddress(addr);
        cb.group(group)
            .channel(LocalChannel.class);

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group)
            .channel(LocalServerChannel.class)
            .childHandler(new ChannelInitializer<LocalChannel>() {
                @Override
                public void initChannel(LocalChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                }
            });

        // Start server
        Channel sc = sb.bind(addr).syncUninterruptibly().channel();
        CountingChannelPoolHandler handler = new CountingChannelPoolHandler();

        int maxChannels = 8;
        final ChannelPool pool = new FixedChannelPool(cb, handler, maxChannels, Integer.MAX_VALUE);

        ExecutorService executor = Executors.newCachedThreadPool();
        final int n = 50;
        final int m = n * 1000;
        final CountDownLatch latch = new CountDownLatch(n);
        final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());
        final AtomicInteger rounds = new AtomicInteger();
        for (int i=0 ; i<n ; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Channel channel;
                        for (int i = 0; i < m; i++) {
                            rounds.incrementAndGet();
                            try {
                                Future<Channel> future1 = pool.acquire().syncUninterruptibly();
                                assertTrue(future1.isSuccess());
                                channel = future1.getNow();
                                assertNotNull(channel);
                                assertTrue(channel.isActive());
                                assertTrue(channel.isOpen());
                                channels.add(channel);
                            }
                            catch (Throwable e) {
                                if (e.getMessage() == null || !e.getMessage().equals("connection refused")) {
                                    System.out.println("Acquire error: " + e.getClass().getName() + ": " + e.getMessage());
                                }
                                continue;
                            }
                            try {
                                channel.close().syncUninterruptibly();
                            }
                            catch (Throwable e) {
                                System.out.println("Close error: " + e.getClass().getName() + ": " + e.getMessage());
                            }
                            try {
                                pool.release(channel).syncUninterruptibly();
                            }
                            catch (IllegalStateException e) {
                                if (!e.getMessage().equals("Channel is unhealthy not offering it back to pool")) {
                                    throw e;
                                }
                            }
                            catch (Throwable e) {
                                System.err.println("Release error: " + e.getClass().getName() + ": " + e.getMessage());
                            }
                        }
                    }
                    catch (Throwable e) {
                        System.err.println("Error: " + e.getClass().getName() + ": " + e.getMessage());
                        if (e instanceof ClosedChannelException) {
                            e.printStackTrace(System.err);
                        }
                    }
                    finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        Thread.sleep(500);

//        assertEquals(channels.size(), handler.channelCount());
//        assertEquals(rounds.get() - channels.size(), handler.acquiredCount());
//        assertEquals(rounds.get(), handler.releasedCount());

        sc.close().syncUninterruptibly();
        for (Channel channel : channels) {
            channel.close().syncUninterruptibly();
        }
        group.shutdownGracefully();

        // Check internal acquired count.
        Field acquiredChannelCountField = FixedChannelPool.class.getDeclaredField("acquiredChannelCount");
        acquiredChannelCountField.setAccessible(true);
        int acquiredChannelCount = (Integer) acquiredChannelCountField.get(pool);
        assertEquals(0, acquiredChannelCount);
    }

    private static final class TestChannelPoolHandler extends AbstractChannelPoolHandler {
        @Override
        public void channelCreated(Channel ch) throws Exception {
            // NOOP
        }
    }
}
