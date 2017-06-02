package org.spf4j.perf.impl.ms.graphite;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeoutException;
import javax.net.SocketFactory;
import org.spf4j.base.Handler;
import org.spf4j.perf.MeasurementsInfo;
import org.spf4j.perf.MeasurementStore;
import org.spf4j.perf.impl.ms.Id2Info;
import static org.spf4j.perf.impl.ms.graphite.GraphiteUdpStore.writeMetric;
import org.spf4j.recyclable.ObjectCreationException;
import org.spf4j.recyclable.ObjectDisposeException;
import org.spf4j.recyclable.RecyclingSupplier;
import org.spf4j.recyclable.Template;
import org.spf4j.recyclable.impl.RecyclingSupplierBuilder;

/**
 *
 * @author zoly
 */
public final class GraphiteTcpStore implements MeasurementStore {

  private final RecyclingSupplier<Writer> socketWriterSupplier;

  private final InetSocketAddress address;

  private static class WriterSupplierFactory implements RecyclingSupplier.Factory<Writer> {

    private final String hostName;
    private final int port;
    private final SocketFactory socketFactory;

    WriterSupplierFactory(final SocketFactory socketFactory, final String hostName, final int port) {
      this.hostName = hostName;
      this.port = port;
      this.socketFactory = socketFactory;
    }

    @Override
    public Writer create() throws ObjectCreationException {
      Socket socket;
      try {
        socket = socketFactory.createSocket(hostName, port);
      } catch (IOException ex) {
        throw new ObjectCreationException(ex);
      }
      try {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8));
      } catch (IOException ex) {
        try {
          socket.close();
        } catch (IOException ex1) {
          ex1.addSuppressed(ex);
          throw new ObjectCreationException(ex1);
        }
        throw new ObjectCreationException(ex);
      }
    }

    @Override
    public void dispose(final Writer object) throws ObjectDisposeException {
      try {
        object.close();
      } catch (IOException ex) {
        throw new ObjectDisposeException(ex);
      }
    }

    @Override
    public boolean validate(final Writer object, final Exception e) {
      return e == null || !(Throwables.getRootCause(e) instanceof IOException);
    }

  }

  public GraphiteTcpStore(final String hostPort) throws ObjectCreationException, URISyntaxException {
    this(new URI("graphiteTcp://" + hostPort));
  }

  public GraphiteTcpStore(final URI uri) throws ObjectCreationException {
    this(uri.getHost(), uri.getPort());
  }

  public GraphiteTcpStore(final String hostName, final int port) throws ObjectCreationException {
    this(hostName, port, SocketFactory.getDefault());
  }

  public GraphiteTcpStore(final String hostName, final int port, final SocketFactory socketFactory)
          throws ObjectCreationException {
    address = new InetSocketAddress(hostName, port);
    socketWriterSupplier = new RecyclingSupplierBuilder<>(1,
            new WriterSupplierFactory(socketFactory, hostName, port)).build();
  }

  @Override
  public long alocateMeasurements(final MeasurementsInfo measurement, final int sampleTimeMillis) {
    return Id2Info.getId(measurement);
  }

  @Override
  @SuppressFBWarnings("BED_BOGUS_EXCEPTION_DECLARATION") // fb nonsense
  public void saveMeasurements(final long tableId,
          final long timeStampMillis, final long... measurements) throws IOException {
    try {
      Template.doOnSupplied(new HandlerImpl(measurements, Id2Info.getInfo(tableId), timeStampMillis),
              socketWriterSupplier, 3, 1000, 60000, IOException.class);
    } catch (InterruptedException | TimeoutException ex) {
      throw new RuntimeException(ex);
    }

  }

  @Override
  public String toString() {
    return "GraphiteTcpStore{address=" + address + '}';
  }

  @Override
  public void close() {
    try {
      socketWriterSupplier.dispose();
    } catch (ObjectDisposeException | InterruptedException ex) {
      throw new UncheckedExecutionException(ex);
    }
  }

  private static class HandlerImpl implements Handler<Writer, IOException> {

    private final long[] measurements;
    private final MeasurementsInfo measurementInfo;
    private final long timeStampMillis;

    HandlerImpl(final long[] measurements, final MeasurementsInfo measurementInfo,
            final long timeStampMillis) {
      this.measurements = measurements;
      this.measurementInfo = measurementInfo;
      this.timeStampMillis = timeStampMillis;
    }

    @Override
    public void handle(final Writer socketWriter, final long deadline) throws IOException {
      for (int i = 0; i < measurements.length; i++) {
        writeMetric(measurementInfo, measurementInfo.getMeasurementName(i),
                measurements[i], timeStampMillis, socketWriter);
      }
      socketWriter.flush();
    }
  }

  @Override
  public void flush() {
    // No buffering yet
  }

}
