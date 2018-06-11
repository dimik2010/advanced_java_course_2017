package edu.technopolis.advancedjava.season2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Сервер, построенный на API java.nio.* . Работает единственный поток,
 * обрабатывающий события, полученные из селектора.
 * Нельзя блокировать или нагружать долгоиграющими действиями данный поток, потому что это
 * замедлит обработку соединений.
 */
public class Server {
  private static final Logger logger = LogManager.getLogger(Server.class);
  public static final int BUF_SIZE = 1024; //1 KB for ByteBuffer per each channel
  private List<SocksClient> clients = new ArrayList<>();


  public void launch() {
    try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
         Selector selector = Selector.open()) {
      serverChannel.configureBlocking(false);
      serverChannel.bind(new InetSocketAddress(10001));
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);
      while (true) {
        selector.select(); //блокирующий вызов
//        @NotNull
        Set<SelectionKey> keys = selector.selectedKeys();
        if (keys.isEmpty()) {
          continue;
        }
        //при обработке ключей из множества selected, необходимо обязательно очищать множество.
        //иначе те же ключи могут быть обработаны снова
        keys.removeIf(key -> {
          if (!key.isValid()) {
            return true;
          }
          if (key.isAcceptable()) {
            return accept(key);
          }
          if (key.isReadable()) {
            //Внимание!!!
            //Важно, чтобы при обработке не было долгоиграющих (например, блокирующих операций),
            //поскольку текущий поток занимается диспетчеризацией каналов и должен быть всегда доступен

            return read(key, selector);
          }
//          if (key.isWritable()) {
//            //Внимание!!!
//            //Важно, чтобы при обработке не было долгоиграющих (например, блокирующих операций),
//            //поскольку текущий поток занимается диспетчеризацией каналов и должен быть всегда доступен
//            return write(map, key);
//          }
          return true;
        });
        clients.removeIf((SocksClient::isClosed));
      }

    } catch (IOException e) {
      logger.error("Unexpected error on processing incoming connection", e);
    }
  }

  private boolean write(Map<SocketChannel, ByteBuffer> map, SelectionKey key) {
    SocketChannel channel = (SocketChannel) key.channel();
    ByteBuffer buffer = map.get(channel);
    try {
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      buffer.compact();
      key.interestOps(SelectionKey.OP_READ);
    } catch (IOException e) {
      closeChannel(channel);
      e.printStackTrace();
    }
    return true;
  }

  private boolean read(SelectionKey key, Selector selector) {
    SocketChannel channel = (SocketChannel) key.channel();
    for (SocksClient client : clients) {
      try {
        if (client.readNewDataIfEquals(selector, channel)) {
          break;
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return true;
  }

  private boolean accept(SelectionKey key) {
    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
    logger.debug("ACCEPTING CHANNEL " + serverChannel);
    SocketChannel channel = null;
    try {
      channel = serverChannel.accept(); //non-blocking call
      channel.configureBlocking(false);
      channel.register(key.selector(), SelectionKey.OP_READ);
      clients.add(new SocksClient(channel));
    } catch (IOException e) {
      logger.error("Failed to process channel " + channel, e);
      if (channel != null) {
        closeChannel(channel);
      }
    }
    return true;
  }

  private void closeChannel(SocketChannel channel) {
    try {
      channel.close();
    } catch (IOException e) {
      logger.error("FAILED TO CLOSE UNREGISTERED CHANNEL " + e);
    }
  }

  public static void main(String[] args) {
    Server server = new Server();
    server.launch();
  }
}
