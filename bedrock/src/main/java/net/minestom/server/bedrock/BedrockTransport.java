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

import java.util.ArrayList;
import java.util.List;

record BedrockTransport(
        String name,
        IoHandlerFactory ioHandlerFactory,
        Class<? extends DatagramChannel> datagramChannel) {

    static BedrockTransport select() {
        if (Epoll.isAvailable()) return epoll();
        if (KQueue.isAvailable()) return kqueue();
        return nio();
    }

    static List<BedrockTransport> available() {
        final List<BedrockTransport> transports = new ArrayList<>();
        transports.add(nio());
        if (Epoll.isAvailable()) transports.add(epoll());
        if (KQueue.isAvailable()) transports.add(kqueue());
        return List.copyOf(transports);
    }

    private static BedrockTransport nio() {
        return new BedrockTransport(
                "nio", NioIoHandler.newFactory(), NioDatagramChannel.class);
    }

    private static BedrockTransport epoll() {
        return new BedrockTransport(
                "epoll", EpollIoHandler.newFactory(), EpollDatagramChannel.class);
    }

    private static BedrockTransport kqueue() {
        return new BedrockTransport(
                "kqueue", KQueueIoHandler.newFactory(), KQueueDatagramChannel.class);
    }
}
