package com.github.cjqcn.socks5.client.handler;

import com.github.cjqcn.socks5.common.DestVisitAddress;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {

    private final static EventLoopGroup proxyGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("proxy-thread"));

    private static final Logger LOG = LoggerFactory.getLogger(Socks5CommandRequestHandler.class);


    @Override
    protected void channelRead0(final ChannelHandlerContext clientChannelContext, final DefaultSocks5CommandRequest msg) throws Exception {

        if (msg.type().equals(Socks5CommandType.CONNECT)) {
            LOG.trace("准备连接目标服务器");

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(proxyGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new Dest2ClientHandler(clientChannelContext, new DestVisitAddress(msg.dstAddr(), msg.dstPort())));
                        }
                    });
            ChannelFuture future = bootstrap.connect("127.0.0.1", 11080);
            future.addListener(new ChannelFutureListener() {

                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        LOG.debug("成功连接目标服务器");
                        clientChannelContext.pipeline().addLast(new Client2DestHandler(future));
                        Socks5CommandResponse commandResponse = new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4);
                        clientChannelContext.writeAndFlush(commandResponse);
                    } else {
                        Socks5CommandResponse commandResponse = new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4);
                        clientChannelContext.writeAndFlush(commandResponse);
                    }
                }

            });
        } else {
            clientChannelContext.fireChannelRead(msg);
        }
    }

    /**
     * 将目标服务器信息转发给客户端
     */
    private static class Dest2ClientHandler extends ChannelInboundHandlerAdapter {

        private ChannelHandlerContext clientChannelContext;

        private static final Logger LOG = LoggerFactory.getLogger(Dest2ClientHandler.class);

        public DestVisitAddress destVisitAddress;

        public Dest2ClientHandler(ChannelHandlerContext clientChannelContext, DestVisitAddress destVisitAddress) {
            this.clientChannelContext = clientChannelContext;
            this.destVisitAddress = destVisitAddress;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            LOG.debug("请求连接：{}", destVisitAddress);
            final ByteBuf buf = Unpooled.copiedBuffer(destVisitAddress.toString() + "zjp", CharsetUtil.UTF_8);
            ctx.writeAndFlush(buf);
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx2, Object destMsg) throws Exception {
            LOG.trace("将目标服务器信息转发给客户端");
            clientChannelContext.writeAndFlush(destMsg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOG.trace("目标服务器断开连接");
            super.channelInactive(ctx);
            clientChannelContext.channel().close();
        }
    }

    /**
     * 将客户端的消息转发给目标服务器端
     */
    private static class Client2DestHandler extends ChannelInboundHandlerAdapter {

        private ChannelFuture destChannelFuture;

        public Client2DestHandler(ChannelFuture destChannelFuture) {
            this.destChannelFuture = destChannelFuture;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            LOG.trace("将客户端的消息转发给目标服务器端");
            destChannelFuture.channel().writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOG.trace("客户端断开连接");
            super.channelInactive(ctx);
            destChannelFuture.channel().close();
        }
    }
}
