package org.stagemonitor.requestmonitor;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.uber.jaeger.Span;
import com.uber.jaeger.context.TracingUtils;
import com.uber.jaeger.reporters.LoggingReporter;
import com.uber.jaeger.samplers.ConstSampler;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.configuration.Configuration;
import org.stagemonitor.core.elasticsearch.ElasticsearchClient;
import org.stagemonitor.core.metrics.metrics2.Metric2Filter;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.core.metrics.metrics2.MetricName;
import org.stagemonitor.requestmonitor.reporter.jaeger.LoggingSpanReporter;
import org.stagemonitor.requestmonitor.tracing.wrapper.SpanWrapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.opentracing.Tracer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;

public class RequestMonitorTest {

	private CorePlugin corePlugin;
	private RequestMonitorPlugin requestMonitorPlugin;
	private Metric2Registry registry;
	private RequestMonitor requestMonitor;
	private Configuration configuration;
	private Map<String, Object> tags = new HashMap<>();

	@Before
	public void before() {
		requestMonitorPlugin = mock(RequestMonitorPlugin.class);
		configuration = mock(Configuration.class);
		corePlugin = mock(CorePlugin.class);
		registry = mock(Metric2Registry.class);

		doReturn(corePlugin).when(configuration).getConfig(CorePlugin.class);
		doReturn(requestMonitorPlugin).when(configuration).getConfig(RequestMonitorPlugin.class);

		doReturn(true).when(corePlugin).isStagemonitorActive();
		doReturn(1000).when(corePlugin).getThreadPoolQueueCapacityLimit();
		doReturn(new Metric2Registry()).when(corePlugin).getMetricRegistry();
		doReturn(Collections.singletonList("http://mockhost:9200")).when(corePlugin).getElasticsearchUrls();
		ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
		doReturn(true).when(elasticsearchClient).isElasticsearchAvailable();
		doReturn(elasticsearchClient).when(corePlugin).getElasticsearchClient();
		doReturn(false).when(corePlugin).isOnlyLogElasticsearchMetricReports();

		doReturn(true).when(requestMonitorPlugin).isCollectRequestStats();
		doReturn(true).when(requestMonitorPlugin).isProfilerActive();

		doReturn(1000000d).when(requestMonitorPlugin).getOnlyReportNRequestsPerMinuteToElasticsearch();
		doReturn(mock(Timer.class)).when(registry).timer(any(MetricName.class));
		doReturn(mock(Meter.class)).when(registry).meter(any(MetricName.class));
		requestMonitor = new RequestMonitor(configuration, registry);
		requestMonitor.addReporter(new LoggingSpanReporter());
		when(requestMonitorPlugin.isLogCallStacks()).thenReturn(true);
		when(requestMonitorPlugin.getRequestMonitor()).thenReturn(requestMonitor);

		final Tracer jaegerTracer = new com.uber.jaeger.Tracer.Builder("RequestMonitorTest", new LoggingReporter(), new ConstSampler(true)).build();
		final Tracer tracer = RequestMonitorPlugin.getSpanWrappingTracer(jaegerTracer, registry,
				requestMonitorPlugin, requestMonitor, TagRecordingSpanInterceptor.asList(tags));
		when(requestMonitorPlugin.getTracer()).thenReturn(tracer);
	}

	@After
	public void after() {
		Stagemonitor.getMetric2Registry().removeMatching(Metric2Filter.ALL);
		Stagemonitor.reset();
	}

	@Test
	public void testDeactivated() throws Exception {
		doReturn(false).when(corePlugin).isStagemonitorActive();

		final RequestMonitor.RequestInformation requestInformation = requestMonitor.monitor(createMonitoredRequest());

		assertEquals("test", requestInformation.getExecutionResult());
		assertNull(requestInformation.getSpan());
	}

	@Test
	public void testNotWarmedUp() throws Exception {
		doReturn(2).when(requestMonitorPlugin).getNoOfWarmupRequests();
		requestMonitor = new RequestMonitor(configuration, registry);
		final RequestMonitor.RequestInformation requestInformation = requestMonitor.monitor(createMonitoredRequest());
		assertNull(requestInformation.getSpan());
	}

	@Test
	public void testRecordException() throws Exception {
		final MonitoredRequest monitoredRequest = createMonitoredRequest();
		doThrow(new RuntimeException("test")).when(monitoredRequest).execute();

		doAnswer(new Answer<Void>() {
			@Override
			public Void answer(InvocationOnMock invocation) throws Throwable {
				RequestMonitor.RequestInformation requestInformation = (RequestMonitor.RequestInformation) invocation.getArguments()[0];
				assertNotNull(requestInformation.getSpan());
				assertEquals("java.lang.RuntimeException", tags.get("exception.class"));
				assertEquals("test", tags.get("exception.message"));
				assertNotNull(tags.get("exception.stack_trace"));
				return null;
			}
		}).when(monitoredRequest).onPostExecute(Mockito.<RequestMonitor.RequestInformation>any());

		try {
			requestMonitor.monitor(monitoredRequest);
		} catch (Exception e) {
		}
	}

	@Test
	public void testInternalMetricsDeactive() throws Exception {
		internalMonitoringTestHelper(false);
	}

	@Test
	public void testInternalMetricsActive() throws Exception {
		doReturn(true).when(corePlugin).isInternalMonitoringActive();

		requestMonitor.monitor(createMonitoredRequest());
		verify(registry, times(0)).timer(name("internal_overhead_request_monitor").build());

		requestMonitor.monitor(createMonitoredRequest());
		verify(registry, times(1)).timer(name("internal_overhead_request_monitor").build());
	}

	private void internalMonitoringTestHelper(boolean active) throws Exception {
		doReturn(active).when(corePlugin).isInternalMonitoringActive();
		requestMonitor.monitor(createMonitoredRequest());
		verify(registry, times(active ? 1 : 0)).timer(name("internal_overhead_request_monitor").build());
	}

	private MonitoredRequest createMonitoredRequest() throws Exception {
		return Mockito.spy(new MonitoredMethodRequest(configuration, "test", () -> "test"));
	}

	@Test
	public void testProfileThisExecutionDeactive() throws Exception {
		doReturn(0d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		final RequestMonitor.RequestInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNull(monitor.getCallTree());
	}

	@Test
	public void testProfileThisExecutionAlwaysActive() throws Exception {
		doReturn(1000000d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		final RequestMonitor.RequestInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNotNull(monitor.getCallTree());
	}

	@Test
	public void testDontActivateProfilerWhenNoRequestTraceReporterIsActive() throws Exception {
		// don't profile if no one is interested in the result
		doReturn(0d).when(requestMonitorPlugin).getOnlyReportNRequestsPerMinuteToElasticsearch();
		doReturn(0d).when(requestMonitorPlugin).getOnlyReportNExternalRequestsPerMinute();
		doReturn(1000000d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		doReturn(false).when(requestMonitorPlugin).isLogCallStacks();
		final RequestMonitor.RequestInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNull(monitor.getCallTree());
	}

	@Test
	public void testProfileThisExecutionActiveEvery2Requests() throws Exception {
		doReturn(2d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		testProfileThisExecutionHelper(0, true);
		testProfileThisExecutionHelper(1.99, true);
		testProfileThisExecutionHelper(2, false);
		testProfileThisExecutionHelper(3, false);
		testProfileThisExecutionHelper(1, true);
	}

	private void testProfileThisExecutionHelper(double callTreeRate, boolean callStackExpected) throws Exception {
		final Meter callTreeMeter = mock(Meter.class);
		doReturn(callTreeRate).when(callTreeMeter).getOneMinuteRate();
		requestMonitor.setCallTreeMeter(callTreeMeter);

		final RequestMonitor.RequestInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		if (callStackExpected) {
			assertNotNull(monitor.getCallTree());
		} else {
			assertNull(monitor.getCallTree());
		}
	}

	@Test
	@Ignore
	public void testGetInstanceNameFromExecution() throws Exception {
		final MonitoredRequest monitoredRequest = createMonitoredRequest();
		doReturn("testInstance").when(monitoredRequest).getInstanceName();
		requestMonitor.monitor(monitoredRequest);
		assertEquals("testInstance", Stagemonitor.getMeasurementSession().getInstanceName());
	}

	@Test
	public void testExecutorServiceContextPropagation() throws Exception {
		SpanCapturingReporter spanCapturingReporter = new SpanCapturingReporter(requestMonitor);

		final ExecutorService executorService = TracingUtils.tracedExecutor(Executors.newSingleThreadExecutor());
		final RequestMonitor.RequestInformation[] asyncSpan = new RequestMonitor.RequestInformation[1];

		final RequestMonitor.RequestInformation testInfo = requestMonitor.monitor(new MonitoredMethodRequest(configuration, "test", () -> {
			assertNotNull(TracingUtils.getTraceContext().getCurrentSpan());
			return monitorAsyncMethodCall(executorService, asyncSpan);
		}));
		executorService.shutdown();
		// waiting for completion
		spanCapturingReporter.get();
		spanCapturingReporter.get();
		((Future<?>) testInfo.getExecutionResult()).get();
		assertEquals("test", testInfo.getOperationName());
		assertEquals("async", asyncSpan[0].getOperationName());

		final long spanID = ((SpanWrapper) testInfo.getSpan()).unwrap(Span.class).context().getSpanID();
		final long parentID = ((SpanWrapper) asyncSpan[0].getSpan()).unwrap(Span.class).context().getParentID();
		assertEquals(spanID, parentID);
	}

	private Future<?> monitorAsyncMethodCall(ExecutorService executorService, final RequestMonitor.RequestInformation[] asyncSpan) {
		return executorService.submit((Callable<Object>) () ->
				asyncSpan[0] = requestMonitor.monitor(new MonitoredMethodRequest(configuration, "async", () -> {
					assertNotNull(TracingUtils.getTraceContext().getCurrentSpan());
					return callAsyncMethod();
				})));
	}

	private Object callAsyncMethod() {
		return null;
	}
}
