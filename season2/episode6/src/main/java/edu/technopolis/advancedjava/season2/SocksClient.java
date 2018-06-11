package edu.technopolis.advancedjava.season2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class SocksClient {
  private static final Logger logger = LogManager.getLogger(SocksClient.class);
  private static final byte SOCKS_VERSION = 0x05;
  private static final byte TCP_IP_CONNECTION_CODE = 0x01;
  private SocketChannel client;
  private SocketChannel destination;
  private int timesLeftToFullyAuth = 2;
  private ByteBuffer transferBuffer = ByteBuffer.allocateDirect(Server.BUF_SIZE);
  private boolean isClosed = false;


  public boolean isClosed() {
    return isClosed;
  }

  public SocksClient(SocketChannel client) {
    this.client = client;
    logger.info("new client with " + client);
  }

  public boolean readNewDataIfEquals(Selector selector, SocketChannel channel) throws IOException {
    if (channel.equals(client)) {
      return (timesLeftToFullyAuth == 0) ? transferData(client, destination) : completeAuth(selector);
    }
    if (channel.equals(destination)) {
      return transferData(destination, client);
    }
    return false;
  }

  private boolean transferData(SocketChannel inputChannel, SocketChannel targetChannel) {
    try {
      int bytesRead = inputChannel.read(transferBuffer);
      if (bytesRead == 0) {
        return true;
      }
      if (bytesRead == -1) {
        destroyClient();
        return true;
      }
      transferBuffer.flip();
      while(transferBuffer.hasRemaining()) {
        targetChannel.write(transferBuffer);
      }
      transferBuffer.compact();
    } catch (IOException e) {
      logger.error("FAILED TO TRANSFER DATA " + e);
    }
    return false;
  }

  private void destroyClient() {
    isClosed = true;
    try {
      client.close();
      if (destination != null) {
        destination.close();
      }
    } catch (IOException e) {
      logger.error("FAILED TO CLOSE CLIENT CHANNEL" + e);
    }
  }

  private boolean completeAuth(Selector selector) throws IOException {
    switch (timesLeftToFullyAuth) {
      case 2:
        return firstAuth(selector);
      case 1:
        return secondAuth(selector);
      default:
        return false;
    }
  }

  private boolean firstAuth(Selector selector) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(10);
    if (client.read(buffer) < 1) {
      return true;
    }
    buffer.flip();
    byte[] str = new byte[buffer.limit()];
    buffer.get(str);
    System.out.println(new String(str));
//    if (buffer.limit() > 3 && buffer.get(3) == 0x02) {
//      ByteBuffer out = ByteBuffer.allocate(2);
//      out.put((byte) 0x05);
//      out.put((byte) 0x02);
//      out.compact();
//      while (out.hasRemaining()) {
//        client.write(out);
//      }
//    } else {
      ByteBuffer out = ByteBuffer.allocate(2);
      out.put((byte) 0x05);
      out.put((byte) 0x00);
      out.compact();
      while (out.hasRemaining()) {
        client.write(out);
      }
//    }
    timesLeftToFullyAuth--;
    return true;
  }


  private boolean secondAuth(Selector selector) throws IOException {
    ByteBuffer authBuffer = ByteBuffer.allocate(512);
    ByteBuffer serverResponse = ByteBuffer.allocate(512);
    if (client.read(authBuffer) < 1) {
      return true;
    }
    byte errorCode = 0x00;
    authBuffer.flip();
    serverResponse.put(1, SOCKS_VERSION);
    int clientSocksVersion = authBuffer.get();
    if (clientSocksVersion != SOCKS_VERSION) {
      errorCode = 0x07; //protocol mistake: command is not supported (incorrect SOCKS version)
    }
    if (authBuffer.get() != TCP_IP_CONNECTION_CODE) { //command code: support only TCP/IP connection
      errorCode = 0x07; //protocol mistake: command is not supported
    }
    authBuffer.get(); //reserved byte must be 0x00
    byte typeOfAddress = authBuffer.get();
    InetAddress address = getAddress(authBuffer, typeOfAddress);
    int portNumber = getPortNumber(authBuffer);
    try {
      Socket destinationSocket = new Socket();
      destinationSocket.connect(new InetSocketAddress(address, portNumber), 300);
      logger.debug("Trying to connect to " + address.toString());
      if (destinationSocket.isConnected()) {
        destination = SocketChannel.open(new InetSocketAddress(address, portNumber));
      }
    } catch (SocketTimeoutException e) {
      errorCode = 0x04; //host is unavailable
      logger.error("FAILED TO CONNECT TO " + address.toString());
    }
    if (destination != null) {
      destination.configureBlocking(false);
      destination.register(selector, SelectionKey.OP_READ);
      logger.debug(("Client " + client + " registered destination channel " + destination));
    }
    serverResponse = generateServerResponse(serverResponse, errorCode, typeOfAddress, address, portNumber);
    while (serverResponse.hasRemaining()) {
      client.write(serverResponse);
    }
    if (errorCode == 0x04) {
      destroyClient();
    }
    timesLeftToFullyAuth--;
    return true;
  }


  private ByteBuffer generateServerResponse(ByteBuffer buffer, byte error, byte typeOfAddress,
                                            InetAddress address, int port) {
    buffer.clear();
    buffer.put((byte) 0x05); //socks version 5
    buffer.put(error); //error byte
    buffer.put((byte) 0x00); //reserved byte, must be 0x00
    buffer.put(typeOfAddress); //type of address type
    if (typeOfAddress == 0x01) { //ipv4
      buffer.put(address.getAddress());
    } else if (typeOfAddress == 0x02) { //hostname
      buffer.put((byte) address.getHostName().getBytes().length);
      buffer.put(address.getHostName().getBytes());
    } else if (typeOfAddress == 0x03) { //ipv6
      buffer.put(address.getAddress());
    }
    buffer.putInt(port);
    buffer.flip();
    return buffer;
  }

  private short getPortNumber(ByteBuffer buffer) {
    return buffer.getShort();
  }

  private InetAddress getAddress(ByteBuffer authBuffer, byte typeOfAddress) throws UnknownHostException {
    byte[] address; //ipv4 or hostname or ipv6 - depends on typeOfAddress
    InetAddress inetAddress = null;
    if (typeOfAddress == (byte) 0x01) { //means that address is ipv4
      address = getIpv4(authBuffer);
      inetAddress = InetAddress.getByAddress(address);
    } else if (typeOfAddress == (byte) 0x02) { //address is host name
      address = getHostName(authBuffer);
      inetAddress = InetAddress.getByName(new String(address));
    } else if (typeOfAddress == (byte) 0x03) { //address is ipv6
      address = getIpv6(authBuffer);
      inetAddress = InetAddress.getByAddress(address);
    }
    return inetAddress;
  }

  private byte[] getIpv6(ByteBuffer buffer) {
    byte[] result = new byte[16];
    buffer.get(result);
    return result;
  }

  private byte[] getHostName(ByteBuffer authBuffer) {
    int length = authBuffer.get();
    byte[] result = new byte[length];
    authBuffer.get(result);
    return result;
  }

  private byte[] getIpv4(ByteBuffer buffer) {
    byte[] address = new byte[4];
    buffer.get(address);
    return address;
  }

}
