/*
 * Copyright 2018 SPF4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spf4j.test.log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Zoltan Farkas
 */
abstract class UncaughtExceptionAsserter implements AsyncObservationAssert, UncaughtExceptionConsumer, Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(UncaughtExceptionAsserter.class);

  private final Matcher<UncaughtExceptionDetail> matcher;

  private final SynchronousQueue<UncaughtExceptionDetail> receiveQueue;

  private final SynchronousQueue<Boolean> replyQueue;

  private final Thread thread;

  private long deadlineNanos;

  private boolean assertSeenMatching;

  private final CountDownLatch isReading;

  private volatile Throwable exception;
  private volatile boolean finished;

  UncaughtExceptionAsserter(final Matcher<UncaughtExceptionDetail> matcher, final boolean assertSeenMatching) {
    this.matcher = matcher;
    this.exception = null;
    this.finished = false;
    this.assertSeenMatching = assertSeenMatching;
    receiveQueue = new SynchronousQueue<>();
    replyQueue = new SynchronousQueue<>();
    deadlineNanos = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
    this.isReading = new CountDownLatch(1);
    thread = new Thread(this);
    thread.start();
  }

  public void waitUntilReading() {
    try {
      if (!isReading.await(1, TimeUnit.MINUTES)) {
        throw new IllegalStateException("Somethig is wrong with " + this);
      }
    } catch (InterruptedException ex) {
      throw new AssertionError("Tests interrupted", ex);
    }
  }

  abstract void unregister();

  @Override
  public void assertObservation(final long timeout, final TimeUnit unit) throws InterruptedException {
    setDeadlineNanos(System.nanoTime() + unit.toNanos(timeout));
    thread.join(unit.toMillis(timeout * 2));
    if (!finished) {
      throw new AssertionError("Assertion thread still running, this = " + this);
    }
    Throwable ex = exception;
    if (ex instanceof AssertionError) {
      throw  (AssertionError) ex;
    } else if (ex != null) {
      throw new AssertionError(ex);
    }
  }

  @Override
  public void run() {
    try {
      if (assertSeenMatching) {
        assertSeen();
      } else {
        assertNotSeen();
      }
    } catch (Throwable error) {
      exception = error;
    }
    finished = true;
  }

  public long getDeadlineNanos() {
    synchronized (thread) {
      return deadlineNanos;
    }
  }

  public void setDeadlineNanos(final long deadlineNanos) {
    synchronized (thread) {
      this.deadlineNanos = deadlineNanos;
    }
    thread.interrupt();
  }


  private void assertNotSeen() throws InterruptedException {
    long timeout;
    isReading.countDown();
    while ((timeout = getDeadlineNanos() - System.nanoTime()) > 0) {
      UncaughtExceptionDetail ucx;
      try {
        ucx = receiveQueue.poll(timeout, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ex) {
        ucx = null;
      }
      if (ucx != null) {
        LOG.trace("{} received {}", this, ucx);
        if (matcher.matches(ucx)) {
          if (!replyQueue.offer(Boolean.TRUE, 10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Other side dissapeared for " + this);
          }
          AssertionError assertionError = new AssertionError("Encountered " + ucx);
          assertionError.addSuppressed(ucx.getThrowable());
          unregister();
          throw assertionError;
        } else {
          if (!replyQueue.offer(Boolean.FALSE, 10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Other side dissapeared for " + this);
          }
        }
      }
    }
    unregister();
  }

  private void assertSeen() throws InterruptedException {
    isReading.countDown();
    long timeout;
    while ((timeout = getDeadlineNanos() - System.nanoTime()) > 0) {
      UncaughtExceptionDetail ucx;
      try {
        ucx = receiveQueue.poll(timeout, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ex) {
        ucx = null;
      }
      if (ucx != null) {
        LOG.trace("{} received {}", this, ucx);
        if (matcher.matches(ucx)) {
          if (!replyQueue.offer(Boolean.TRUE, 10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Other side dissapeared for " + this);
          }
          unregister();
          return;
        } else {
          if (!replyQueue.offer(Boolean.FALSE, 10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Other side dissapeared for " + this);
          }
        }
      }
    }
    unregister();
    throw new AssertionError("Did not observe: " + matcher);
  }

  @Override
  public boolean offer(final UncaughtExceptionDetail exDetail) {
    if (receiveQueue.offer(exDetail)) {
      LOG.trace("Sent {} to {}", exDetail, this);
      try {
        Boolean consumed = replyQueue.poll(10, TimeUnit.SECONDS);
        LOG.trace("Reply with {} from {}", consumed, this);
        if (consumed == null) {
          throw new IllegalStateException("Illegal state " + this);
        }
        return consumed;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return false;
      }
    } else {
      return false;
    }
  }

  @Override
  public String toString() {
    return "UncaughtExceptionAsserter{" + "matcher=" + matcher + '}';
  }

}