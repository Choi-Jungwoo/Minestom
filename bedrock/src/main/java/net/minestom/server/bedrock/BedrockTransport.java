package net.minestom.server.bedrock;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;

record BedrockTransport(
        String name,
        IoHandlerFactory ioHandlerFactory,
        Class<? extends DatagramChannel> datagramChannel) {

    static BedrockTransport select() {
        if (Epoll.isAvailable()) {
            return new BedrockTransport(
                    "epoll", EpollIoHandler.newFactory(), EpollDatagramChannel.class);
        }
        if (KQueue.isAvailable()) {
            return new BedrockTransport(
                    "kqueue", KQueueIoHandler.newFactory(), KQueueDatagramChannel.class);
        }
        return new BedrockTransport(
                "nio", NioIoHandler.newFactory(), NioDatagramChannel.class);
    }
}
