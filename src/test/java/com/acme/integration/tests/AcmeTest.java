package com.acme.integration.tests;

import com.acme.integration.tests.fakeesb.FakeESB;

import nz.ac.auckland.integration.testing.OrchestratedTestBuilder;
import nz.ac.auckland.integration.testing.expectation.MockExpectation;

/**
 * A JUNIT Parameterized Test that executes each test specification as a separate test
 * - this bootstrap code is expected to be reduced further in upcoming releases
 */
public class AcmeTest extends OrchestratedTestBuilder {

    public static void configure() {
        //Sends a request to pingService and validates the response
        syncTest("cxf:http://localhost:8090/services/pingService", "Simple WS PING test")
                .requestBody(xml("<ns:pingRequest xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<request>PING</request>" +
                        "</ns:pingRequest>"))
                .expectedResponseBody(xml("<ns:pingResponse xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<response>PONG</response>" +
                        "</ns:pingResponse>"));

        //Using WS-Security features of CXF (username/password) - note you need to specify the WSDL so
        //that CXF can grab the policy (it can be a remote reference if you wish)
        syncTest("cxf://http://localhost:8090/services/securePingService?wsdlURL=SecurePingService.wsdl&" +
                "properties.ws-security.username=user" +
                "&properties.ws-security.password=pass",
                "Simple WS PING test with WS-Security")
                .requestBody(xml("<ns:pingRequest xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<request>PING</request>" +
                        "</ns:pingRequest>"))
                .expectedResponseBody(xml("<ns:pingResponse xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<response>PONG</response>" +
                        "</ns:pingResponse>"));

        //Using classpath resources instead
        syncTest("cxf:http://localhost:8090/services/pingService",
                "Simple WS PING test with local resources")
                .requestBody(xml(classpath("/data/pingRequest1.xml")))
                .expectedResponseBody(xml(classpath("/data/pingResponse1.xml")));

        //Using a JSON service
        syncTest("http://localhost:8091/jsonPingService", "Simple JSON PING")
                .requestBody(json("{\"request\":\"PING\"}"))
                .expectedResponseBody(json("{\"response\":\"PONG\"}"));

        //Showing how expectations can create mock endpoints to validate the incoming request and provide a canned response
        syncTest("cxf:http://localhost:8090/services/pingServiceProxy",
                "WS PING test with mock service expectation")
                .requestBody(xml(classpath("/data/pingRequest1.xml")))
                .expectedResponseBody(xml(classpath("/data/pingResponse1.xml")))
                .addExpectation(syncExpectation("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl")
                        .expectedBody(xml(classpath("/data/pingRequest1.xml")))
                        .responseBody(xml(classpath("/data/pingResponse1.xml"))));

        //Showing how we can string together expectations for multiple requests to the same (or different) endpoints
        syncTest("cxf:http://localhost:8090/services/pingServiceMultiProxy",
                "WS PING test with multiple mock service expectations")
                .requestBody(xml(classpath("/data/pingRequest1.xml")))
                .expectedResponseBody(xml(classpath("/data/pingResponse1.xml")))
                .addExpectation(syncExpectation("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService" +
                        ".wsdl")
                        .expectedBody(xml(classpath("/data/pingRequest1.xml")))
                        .responseBody(xml(classpath("/data/pingResponse1.xml"))))
                .addExpectation(syncExpectation
                        ("cxf:http://localhost:9091/services/anotherTargetWS?wsdlURL=PingService.wsdl")
                        .expectedBody(xml(classpath("/data/pingRequest1.xml")))
                        .responseBody(xml(classpath("/data/pingResponse1.xml"))));

        //The same as above except showing support for weakly ordered expectations (i.e. multi-threaded call-outs)
        syncTest("cxf:http://localhost:8090/services/pingServiceMultiProxyUnordered",
                "WS PING test with multiple unordered mock service expectations")
                .requestBody(xml(classpath("/data/pingRequest1.xml")))
                .expectedResponseBody(xml(classpath("/data/pingResponse1.xml")))
                .addExpectation(syncExpectation("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl")
                        .expectedBody(xml(classpath("/data/pingRequest1.xml")))
                        .responseBody(xml(classpath("/data/pingResponse1.xml")))
                        .ordering(MockExpectation.OrderingType.PARTIAL))
                .addExpectation(syncExpectation("cxf:http://localhost:9091/services/anotherTargetWS?wsdlURL=PingService.wsdl")
                        .expectedBody(xml(classpath("/data/pingRequest1.xml")))
                        .responseBody(xml(classpath("/data/pingResponse1.xml")))
                        .ordering(MockExpectation.OrderingType.PARTIAL));
        /*
        Send an invalid message to the ESB which validates and rejects it,  meaning the target endpoint shouldn't
         receive it. The unreceivedExpectation is especially useful for message filtering where you want to
         ensure the message doesn't arrive at the endpoint.
         */
        syncTest("cxf:http://localhost:8090/services/pingServiceProxy",
                "Test invalid message doesn't arrive at the endpoint and returns exception")
                .requestBody(xml("<ns:pingRequest xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                                                    "<request>PONG</request>" +
                                                 "</ns:pingRequest>"))
                .expectsExceptionResponse()
                .addExpectation(unreceivedExpectation("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl"));

        //Send a message to a vm destination (like a JMS queue) to show asynchronous messaging with transformation
        asyncTest("vm:test.input", "Simple Asynchronous Canonicalizer Comparison")
                .inputMessage(xml("<SystemField>foo</SystemField>"))
                .addExpectation(asyncExpectation("vm:test.output")
                        .expectedBody(xml("<CanonicalField>foo</CanonicalField>")));
    }

    private FakeESB esb;

    public AcmeTest() throws Exception {
        //This is to set up a magical ESB (you won't normally need to do this)
        esb = new FakeESB();
    }

    /*
    The wizard behind the curtains - sets up and tears down the integration processes for demonstration purposes
    */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        esb.start();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        esb.stop();
    }

}
