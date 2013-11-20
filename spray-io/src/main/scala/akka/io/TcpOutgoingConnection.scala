/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.{ SelectionKey, SocketChannel }
import scala.collection.immutable
import akka.util.NonFatal
import akka.util.duration._
import akka.actor.{ Terminated, ReceiveTimeout, ActorRef }
import akka.io.Inet.SocketOption
import akka.io.TcpConnection.CloseInformation
import akka.io.SelectionHandler._
import akka.io.Tcp._

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 *
 * INTERNAL API
 */
private[io] class TcpOutgoingConnection(_tcp: TcpExt,
                                        channelRegistry: ChannelRegistry,
                                        commander: ActorRef,
                                        connect: Connect)
    extends TcpConnection(_tcp, SocketChannel.open().configureBlocking(false).asInstanceOf[SocketChannel]) {

  import connect._

  context.watch(commander) // sign death pact

  localAddress.foreach(channel.socket.bind)
  options.foreach(_.beforeConnect(channel.socket))
  channelRegistry.register(channel, 0)
  timeout foreach context.setReceiveTimeout //Initiate connection timeout if supplied

  private def stop(): Unit = stopWith(CloseInformation(Set(commander), connect.failureMessage))

  private def reportConnectFailure(thunk: ⇒ Unit): Unit = {
    try {
      thunk
    } catch {
      case NonFatal(e) ⇒
        log.debug("Could not establish connection to [{}] due to {}", remoteAddress, e)
        stop()
    }
  }

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      log.debug("Attempting connection to [{}]", remoteAddress)
      reportConnectFailure {
        if (channel.connect(remoteAddress))
          completeConnect(registration, commander, options)
        else {
          registration.enableInterest(SelectionKey.OP_CONNECT)
          context.become(connecting(registration, tcp.Settings.FinishConnectRetries))
        }
      }

    case Terminated(`commander`) ⇒ onCommanderTerminated()
  }

  def connecting(registration: ChannelRegistration, remainingFinishConnectRetries: Int): Receive = {
    case ChannelConnectable ⇒
      reportConnectFailure {
        if (channel.finishConnect()) {
          if (timeout.isDefined) context.resetReceiveTimeout()
          log.debug("Connection established to [{}]", remoteAddress)
          completeConnect(registration, commander, connect.options)
        } else {
          if (remainingFinishConnectRetries > 0) {
            context.system.scheduler.scheduleOnce(1.millisecond) {
              channelRegistry.register(channel, SelectionKey.OP_CONNECT)
            }
            context.become(connecting(registration, remainingFinishConnectRetries - 1))
          } else {
            log.debug("Could not establish connection because finishConnect " +
              "never returned true (consider increasing akka.io.tcp.finish-connect-retries)")
            stop()
          }
        }
      }

    case ReceiveTimeout ⇒
      if (timeout.isDefined) context.resetReceiveTimeout()
      log.debug("Connect timeout expired, could not establish connection to {}", remoteAddress)
      stop()

    case Terminated(`commander`) ⇒ onCommanderTerminated()
  }

  def onCommanderTerminated(): Unit = {
    log.debug("`commander` was terminated. Stopping...")
    stopWith(CloseInformation(Set.empty, connect.failureMessage))
  }
}
