package ca.uhn.fhir.jpa.bulk;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.bulk.api.BulkDataExportOptions;
import ca.uhn.fhir.jpa.bulk.api.GroupBulkDataExportOptions;
import ca.uhn.fhir.jpa.bulk.api.IBulkDataExportSvc;
import ca.uhn.fhir.jpa.bulk.model.BulkExportResponseJson;
import ca.uhn.fhir.jpa.bulk.model.BulkJobStatusEnum;
import ca.uhn.fhir.jpa.bulk.provider.BulkDataExportProvider;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.apache.ResourceEntity;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.test.utilities.JettyUtil;
import ca.uhn.fhir.util.JsonUtil;
import ca.uhn.fhir.util.UrlUtil;
import com.google.common.base.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BulkDataExportProviderTest {

	private static final String A_JOB_ID = "0000000-AAAAAA";
	private static final Logger ourLog = LoggerFactory.getLogger(BulkDataExportProviderTest.class);
	private static final String GROUP_ID = "Group/G2401";
	private static final String G_JOB_ID = "0000000-GGGGGG";
	private Server myServer;
	private final FhirContext myCtx = FhirContext.forCached(FhirVersionEnum.R4);
	private int myPort;
	@Mock
	private IBulkDataExportSvc myBulkDataExportSvc;
	private CloseableHttpClient myClient;
	@Captor
	private ArgumentCaptor<BulkDataExportOptions> myBulkDataExportOptionsCaptor;
	@Captor
	private ArgumentCaptor<GroupBulkDataExportOptions> myGroupBulkDataExportOptionsCaptor;

	@AfterEach
	public void after() throws Exception {
		JettyUtil.closeServer(myServer);
		myClient.close();
	}

	@BeforeEach
	public void start() throws Exception {
		myServer = new Server(0);

		BulkDataExportProvider provider = new BulkDataExportProvider();
		provider.setBulkDataExportSvcForUnitTests(myBulkDataExportSvc);
		provider.setFhirContextForUnitTest(myCtx);

		ServletHandler proxyHandler = new ServletHandler();
		RestfulServer servlet = new RestfulServer(myCtx);
		servlet.registerProvider(provider);
		ServletHolder servletHolder = new ServletHolder(servlet);
		proxyHandler.addServletWithMapping(servletHolder, "/*");
		myServer.setHandler(proxyHandler);
		JettyUtil.startServer(myServer);
		myPort = JettyUtil.getPortForStartedServer(myServer);

		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(5000, TimeUnit.MILLISECONDS);
		HttpClientBuilder builder = HttpClientBuilder.create();
		builder.setConnectionManager(connectionManager);
		myClient = builder.build();

	}

	@Test
	public void testSuccessfulInitiateBulkRequest_Post() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo()
			.setJobId(A_JOB_ID);
		when(myBulkDataExportSvc.submitJob(any())).thenReturn(jobInfo);

		InstantType now = InstantType.now();

		Parameters input = new Parameters();
		input.addParameter(JpaConstants.PARAM_EXPORT_OUTPUT_FORMAT, new StringType(Constants.CT_FHIR_NDJSON));
		input.addParameter(JpaConstants.PARAM_EXPORT_TYPE, new StringType("Patient, Practitioner"));
		input.addParameter(JpaConstants.PARAM_EXPORT_SINCE, now);
		input.addParameter(JpaConstants.PARAM_EXPORT_TYPE_FILTER, new StringType("Patient?identifier=foo"));

		ourLog.info(myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(input));

		HttpPost post = new HttpPost("http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT);
		post.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		post.setEntity(new ResourceEntity(myCtx, input));
		ourLog.info("Request: {}", post);
		try (CloseableHttpResponse response = myClient.execute(post)) {
			ourLog.info("Response: {}", response.toString());

			assertEquals(202, response.getStatusLine().getStatusCode());
			assertEquals("Accepted", response.getStatusLine().getReasonPhrase());
			assertEquals("http://localhost:" + myPort + "/$export-poll-status?_jobId=" + A_JOB_ID, response.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue());
		}

		verify(myBulkDataExportSvc, times(1)).submitJob(myBulkDataExportOptionsCaptor.capture());
		BulkDataExportOptions options = myBulkDataExportOptionsCaptor.getValue();
		assertEquals(Constants.CT_FHIR_NDJSON, options.getOutputFormat());
		assertThat(options.getResourceTypes(), containsInAnyOrder("Patient", "Practitioner"));
		assertThat(options.getSince(), notNullValue());
		assertThat(options.getFilters(), containsInAnyOrder("Patient?identifier=foo"));
	}

	@Test
	public void testSuccessfulInitiateBulkRequest_Get() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo()
			.setJobId(A_JOB_ID);
		when(myBulkDataExportSvc.submitJob(any())).thenReturn(jobInfo);

		InstantType now = InstantType.now();

		String url = "http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT
			+ "?" + JpaConstants.PARAM_EXPORT_OUTPUT_FORMAT + "=" + UrlUtil.escapeUrlParam(Constants.CT_FHIR_NDJSON)
			+ "&" + JpaConstants.PARAM_EXPORT_TYPE + "=" + UrlUtil.escapeUrlParam("Patient, Practitioner")
			+ "&" + JpaConstants.PARAM_EXPORT_SINCE + "=" + UrlUtil.escapeUrlParam(now.getValueAsString())
			+ "&" + JpaConstants.PARAM_EXPORT_TYPE_FILTER + "=" + UrlUtil.escapeUrlParam("Patient?identifier=foo");

		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		ourLog.info("Request: {}", url);
		try (CloseableHttpResponse response = myClient.execute(get)) {
			ourLog.info("Response: {}", response.toString());

			assertEquals(202, response.getStatusLine().getStatusCode());
			assertEquals("Accepted", response.getStatusLine().getReasonPhrase());
			assertEquals("http://localhost:" + myPort + "/$export-poll-status?_jobId=" + A_JOB_ID, response.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue());
		}

		verify(myBulkDataExportSvc, times(1)).submitJob(myBulkDataExportOptionsCaptor.capture());
		BulkDataExportOptions options = myBulkDataExportOptionsCaptor.getValue();
		assertEquals(Constants.CT_FHIR_NDJSON, options.getOutputFormat());
		assertThat(options.getResourceTypes(), containsInAnyOrder("Patient", "Practitioner"));
		assertThat(options.getSince(), notNullValue());
		assertThat(options.getFilters(), containsInAnyOrder("Patient?identifier=foo"));
	}

	@Test
	public void testPollForStatus_BUILDING() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo()
			.setJobId(A_JOB_ID)
			.setStatus(BulkJobStatusEnum.BUILDING)
			.setStatusTime(InstantType.now().getValue());
		when(myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(eq(A_JOB_ID))).thenReturn(jobInfo);

		String url = "http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT_POLL_STATUS + "?" +
			JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + A_JOB_ID;
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		try (CloseableHttpResponse response = myClient.execute(get)) {
			ourLog.info("Response: {}", response.toString());

			assertEquals(202, response.getStatusLine().getStatusCode());
			assertEquals("Accepted", response.getStatusLine().getReasonPhrase());
			assertEquals("120", response.getFirstHeader(Constants.HEADER_RETRY_AFTER).getValue());
			assertThat(response.getFirstHeader(Constants.HEADER_X_PROGRESS).getValue(), containsString("Build in progress - Status set to BUILDING at 20"));
		}

	}

	@Test
	public void testPollForStatus_ERROR() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo()
			.setJobId(A_JOB_ID)
			.setStatus(BulkJobStatusEnum.ERROR)
			.setStatusTime(InstantType.now().getValue())
			.setStatusMessage("Some Error Message");
		when(myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(eq(A_JOB_ID))).thenReturn(jobInfo);

		String url = "http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT_POLL_STATUS + "?" +
			JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + A_JOB_ID;
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		try (CloseableHttpResponse response = myClient.execute(get)) {
			ourLog.info("Response: {}", response.toString());

			assertEquals(500, response.getStatusLine().getStatusCode());
			assertEquals("Server Error", response.getStatusLine().getReasonPhrase());

			String responseContent = IOUtils.toString(response.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response content: {}", responseContent);
			assertThat(responseContent, containsString("\"diagnostics\": \"Some Error Message\""));
		}

	}

	@Test
	public void testPollForStatus_COMPLETED() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo()
			.setJobId(A_JOB_ID)
			.setStatus(BulkJobStatusEnum.COMPLETE)
			.setStatusTime(InstantType.now().getValue());
		jobInfo.addFile().setResourceType("Patient").setResourceId(new IdType("Binary/111"));
		jobInfo.addFile().setResourceType("Patient").setResourceId(new IdType("Binary/222"));
		jobInfo.addFile().setResourceType("Patient").setResourceId(new IdType("Binary/333"));
		when(myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(eq(A_JOB_ID))).thenReturn(jobInfo);

		String url = "http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT_POLL_STATUS + "?" +
			JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + A_JOB_ID;
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		try (CloseableHttpResponse response = myClient.execute(get)) {
			ourLog.info("Response: {}", response.toString());

			assertEquals(200, response.getStatusLine().getStatusCode());
			assertEquals("OK", response.getStatusLine().getReasonPhrase());
			assertEquals(Constants.CT_JSON, response.getEntity().getContentType().getValue());

			String responseContent = IOUtils.toString(response.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response content: {}", responseContent);
			BulkExportResponseJson responseJson = JsonUtil.deserialize(responseContent, BulkExportResponseJson.class);
			assertEquals(3, responseJson.getOutput().size());
			assertEquals("Patient", responseJson.getOutput().get(0).getType());
			assertEquals("http://localhost:" + myPort + "/Binary/111", responseJson.getOutput().get(0).getUrl());
			assertEquals("Patient", responseJson.getOutput().get(1).getType());
			assertEquals("http://localhost:" + myPort + "/Binary/222", responseJson.getOutput().get(1).getUrl());
			assertEquals("Patient", responseJson.getOutput().get(2).getType());
			assertEquals("http://localhost:" + myPort + "/Binary/333", responseJson.getOutput().get(2).getUrl());
		}

	}

	@Test
	public void testPollForStatus_Gone() throws IOException {

		when(myBulkDataExportSvc.getJobInfoOrThrowResourceNotFound(eq(A_JOB_ID))).thenThrow(new ResourceNotFoundException("Unknown job: AAA"));

		String url = "http://localhost:" + myPort + "/" + JpaConstants.OPERATION_EXPORT_POLL_STATUS + "?" +
			JpaConstants.PARAM_EXPORT_POLL_STATUS_JOB_ID + "=" + A_JOB_ID;
		HttpGet get = new HttpGet(url);
		get.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		try (CloseableHttpResponse response = myClient.execute(get)) {
			ourLog.info("Response: {}", response.toString());
			String responseContent = IOUtils.toString(response.getEntity().getContent(), Charsets.UTF_8);
			ourLog.info("Response content: {}", responseContent);

			assertEquals(404, response.getStatusLine().getStatusCode());
			assertEquals(Constants.CT_FHIR_JSON_NEW, response.getEntity().getContentType().getValue().replaceAll(";.*", "").trim());
			assertThat(responseContent, containsString("\"diagnostics\":\"Unknown job: AAA\""));
		}

	}

	/**
	 * Group export tests
	 * See https://build.fhir.org/ig/HL7/us-bulk-data/
	 * <p>
	 * GET [fhir base]/Group/[id]/$export
	 * <p>
	 * FHIR Operation to obtain data on all patients listed in a single FHIR Group Resource.
	 */

	@Test
	public void testSuccessfulInitiateGroupBulkRequest_Post() throws IOException {

		IBulkDataExportSvc.JobInfo jobInfo = new IBulkDataExportSvc.JobInfo().setJobId(G_JOB_ID);
		when(myBulkDataExportSvc.submitJob(any())).thenReturn(jobInfo);

		InstantType now = InstantType.now();


		Parameters input = new Parameters();
		StringType obsTypeFilter = new StringType("Observation?code=OBSCODE,DiagnosticReport?code=DRCODE");
		input.addParameter(JpaConstants.PARAM_EXPORT_OUTPUT_FORMAT, new StringType(Constants.CT_FHIR_NDJSON));
		input.addParameter(JpaConstants.PARAM_EXPORT_TYPE, new StringType("Observation, DiagnosticReport"));
		input.addParameter(JpaConstants.PARAM_EXPORT_SINCE, now);
		input.addParameter(JpaConstants.PARAM_EXPORT_MDM, true);
		input.addParameter(JpaConstants.PARAM_EXPORT_TYPE_FILTER, obsTypeFilter);

		ourLog.info(myCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(input));

		HttpPost post = new HttpPost("http://localhost:" + myPort + "/" + GROUP_ID + "/" + JpaConstants.OPERATION_EXPORT);
		post.addHeader(Constants.HEADER_PREFER, Constants.HEADER_PREFER_RESPOND_ASYNC);
		post.setEntity(new ResourceEntity(myCtx, input));
		ourLog.info("Request: {}", post);
		try (CloseableHttpResponse response = myClient.execute(post)) {
			ourLog.info("Response: {}", response.toString());
			assertEquals(202, response.getStatusLine().getStatusCode());
			assertEquals("Accepted", response.getStatusLine().getReasonPhrase());
			assertEquals("http://localhost:" + myPort + "/$export-poll-status?_jobId=" + G_JOB_ID, response.getFirstHeader(Constants.HEADER_CONTENT_LOCATION).getValue());
		}

		verify(myBulkDataExportSvc, times(1)).submitJob(myGroupBulkDataExportOptionsCaptor.capture());
		GroupBulkDataExportOptions options = myGroupBulkDataExportOptionsCaptor.getValue();
		assertEquals(Constants.CT_FHIR_NDJSON, options.getOutputFormat());
		assertThat(options.getResourceTypes(), containsInAnyOrder("Observation", "DiagnosticReport"));
		assertThat(options.getSince(), notNullValue());
		assertThat(options.getFilters(), notNullValue());
		assertEquals(GROUP_ID, options.getGroupId().getValue());
		assertThat(options.isMdm(), is(equalTo(true)));
	}
}
