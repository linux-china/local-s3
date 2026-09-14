package com.robothy.s3.rest.netty;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The handlers of a channel that suspended reading it, e.g. the decoder while the body of a request is written to a
 * file and the message handler while a request is handled, so that one of them doesn't resume reading while another
 * still needs it suspended. The channel reads while no handler suspends it.
 *
 * <p>Only accessed on the event loop of the channel.
 */
final class ReadSuspensions {

  private static final AttributeKey<ReadSuspensions> KEY =
      AttributeKey.valueOf(ReadSuspensions.class, "readSuspensions");

  private final Set<Object> suspenders = Collections.newSetFromMap(new IdentityHashMap<>());

  private ReadSuspensions() {
  }

  /**
   * Stop reading the channel until {@code suspender} resumes it, and no other suspender still suspends it.
   *
   * @param channel the channel.
   * @param suspender the handler that suspends reading.
   */
  static void suspend(Channel channel, Object suspender) {
    of(channel).suspenders.add(suspender);
    channel.config().setAutoRead(false);
  }

  /**
   * Resume reading the channel, unless another suspender still suspends it, or {@code suspender} didn't suspend it.
   *
   * @param channel the channel.
   * @param suspender the handler that suspended reading.
   */
  static void resume(Channel channel, Object suspender) {
    ReadSuspensions suspensions = of(channel);
    if (suspensions.suspenders.remove(suspender) && suspensions.suspenders.isEmpty() && channel.isActive()) {
      channel.config().setAutoRead(true);
    }
  }

  private static ReadSuspensions of(Channel channel) {
    ReadSuspensions suspensions = channel.attr(KEY).get();
    if (suspensions == null) {
      suspensions = new ReadSuspensions();
      channel.attr(KEY).set(suspensions);
    }
    return suspensions;
  }

}
