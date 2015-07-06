package com.acme.integration.tests;

import nz.ac.auckland.morc.MorcTestBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.processor.aggregate.AggregationStrategy;

public class AcmeTest extends MorcTestBuilder {

    //Each of the syncTest or asyncTest calls creates a new JUnit test
    @Override
    public void configure() {
        //Sends a request to pingService and validates the response
        syncTest("Simple WS PING test", "cxf:http://localhost:8090/services/pingService")
                .request(xml("<ns:pingRequest xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<request>PING</request>" +
                        "</ns:pingRequest>"))
                .expectation(xml("<ns:pingResponse xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<response>PONG</response>" +
                        "</ns:pingResponse>"));

        //Using classpath resources instead
        syncTest("Simple WS PING test with local resources",
                "cxf:http://localhost:8090/services/pingService")
                .request(xml(classpath("/data/pingRequest1.xml")))
                .expectation(xml(classpath("/data/pingResponse1.xml")));

        //Using a JSON service
        syncTest("Simple JSON PING", "http://localhost:8091/jsonPingService")
                .request(json("{\"request\":\"PING\"}"))
                .expectation(json("{\"response\":\"PONG\"}"));

        //Showing how expectations can create mock endpoints to validate the incoming request and provide a canned response
        syncTest("WS PING test with mock service expectation", "cxf:http://localhost:8090/services/pingServiceProxy")
                .request(xml(classpath("/data/pingRequest1.xml")))
                .expectation(xml(classpath("/data/pingResponse1.xml")))
                .addMock(syncMock("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl")
                        .expectation(xml(classpath("/data/pingRequest1.xml")))
                        .response(xml(classpath("/data/pingResponse1.xml"))));

        //Showing how we can string together expectations for multiple requests to the same (or different) endpoints
        syncTest("WS PING test with multiple mock service expectations", "cxf:http://localhost:8090/services/pingServiceMultiProxy")
                .request(xml(classpath("/data/pingRequest1.xml")))
                .expectation(xml(classpath("/data/pingResponse1.xml")))
                .addMock(syncMock("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl")
                        .expectation(xml(classpath("/data/pingRequest1.xml")))
                        .response(xml(classpath("/data/pingResponse1.xml"))))
                .addMock(syncMock("cxf:http://localhost:9091/services/anotherTargetWS?wsdlURL=PingService.wsdl")
                        .expectation(xml(classpath("/data/pingRequest1.xml")))
                        .response(xml(classpath("/data/pingResponse1.xml"))));

        //The same as above except showing support for weakly ordered expectations (i.e. multi-threaded call-outs)
        syncTest("WS PING test with multiple unordered mock service expectations",
                "cxf:http://localhost:8090/services/pingServiceMultiProxyUnordered")
                .request(xml(classpath("/data/pingRequest1.xml")))
                .expectation(xml(classpath("/data/pingResponse1.xml")))
                .addMock(syncMock("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl")
                        .expectation(xml(classpath("/data/pingRequest1.xml")))
                        .response(xml(classpath("/data/pingResponse1.xml")))
                        .ordering(partialOrdering()))
                .addMock(syncMock("cxf:http://localhost:9091/services/anotherTargetWS?wsdlURL=PingService.wsdl")
                        .expectation(xml(classpath("/data/pingRequest1.xml")))
                        .response(xml(classpath("/data/pingResponse1.xml")))
                        .ordering(partialOrdering()));
        /*
        Send an invalid message to the ESB which validates and rejects it,  meaning the target endpoint shouldn't
         receive it. The unreceivedExpectation is especially useful for message filtering where you want to
         ensure the message doesn't arrive at the endpoint.
         */
        syncTest("Test invalid message doesn't arrive at the endpoint and returns exception",
                "cxf:http://localhost:8090/services/pingServiceProxy")
                .request(xml("<ns:pingRequest xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                        "<request>PONG</request>" +
                        "</ns:pingRequest>"))
                .expectsException()
                .addMock(unreceivedMock("cxf:http://localhost:9090/services/targetWS?wsdlURL=PingService.wsdl"));

        //Send a message to a vm destination (like a JMS queue) to show asynchronous messaging with transformation
        asyncTest("Simple Asynchronous Canonicalizer Comparison", "vm:test.input")
                .input(xml("<SystemField>foo</SystemField>"))
                .addMock(asyncMock("vm:test.output")
                        .expectation(xml("<CanonicalField>foo</CanonicalField>")));

        //A test to show how we can set up an expectation to throw an HTTP exception and then
        //have it validated correctly
        syncTest("Simple JSON API proxy failure test with body", "jetty:http://localhost:8091/testJSONAPI")
                .request(json("{\"hello\":\"home\"}"))
				.expectsException()
                .expectation(httpErrorResponse(501,json("{\"error\":\"Should be hello:world\"}")))
                .addMock(syncMock("jetty:http://localhost:8091/targetJSONAPI")
                        .expectation(json("{\"hello\":\"home\"}"))
                        .response(httpErrorResponse(501,json("{\"error\":\"Should be hello:world\"}"))));
    }

    /**
     * If you're testing external routes (i.e. an application server service bus) then this wouldn't be here
     * - this just sets up a 'fake' Camel ESB.
     */
    @Override
    public RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:pingServiceResponse")
                        .setBody(constant("<ns:pingResponse xmlns:ns=\"urn:com:acme:integration:wsdl:pingservice\">" +
                                "<response>PONG</response>" +
                                "</ns:pingResponse>"));

                from("cxf:bean:pingService")
                        .to("direct:pingServiceResponse");

                from("cxf:bean:pingServiceProxy")
                        .to("direct:pingServiceProxy");

                from("direct:pingServiceProxy")
                        //do some quick validation to show what happens on error
                        .process(new Processor() {
                            @Override
                            public void process(Exchange exchange) throws Exception {
                                //a poor man's attempt at validation
                                if (!exchange.getIn().getBody(String.class).contains("PING"))
                                    throw new Exception("INVALID BODY");
                            }
                        })
                                //send this through to the 'target' system
                        .to("cxf:http://localhost:9090/services/targetWS?dataFormat=PAYLOAD&wsdlURL=PingService.wsdl");

                from("cxf:bean:pingServiceMultiProxy")
                        .multicast(new AggregationStrategy() {
                            @Override
                            public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                                return newExchange;
                            }
                        }).stopOnException()
                        .to("cxf:http://localhost:9090/services/targetWS?dataFormat=PAYLOAD&wsdlURL=PingService.wsdl",
                                "cxf:http://localhost:9091/services/anotherTargetWS?dataFormat=PAYLOAD&wsdlURL=PingService.wsdl");

                //parallel processing will mean this can happen in any order
                from("cxf:bean:pingServiceMultiProxyUnordered")
                        .multicast(new AggregationStrategy() {
                            @Override
                            public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                                return newExchange;
                            }
                        })
                        .parallelProcessing()
                        .to("direct:targetWSSlowDown",
                                "cxf:http://localhost:9091/services/anotherTargetWS?dataFormat=PAYLOAD&wsdlURL=PingService.wsdl");

                //ensure they arrive out of order by delaying the first one
                from("direct:targetWSSlowDown")
                        .delay(5000)
                        .to("cxf:http://localhost:9090/services/targetWS?dataFormat=PAYLOAD&wsdlURL=PingService.wsdl");

                //The JSON Service is the easiest :)
                from("jetty:http://localhost:8091/jsonPingService")
                        .setBody(constant("{\"response\":\"PONG\"}"));

                //Simple canonicalization service - the vm transport is like a JMS queue
                from("vm:test.input")
                        .setBody(constant("<CanonicalField>foo</CanonicalField>"))
                        .to("vm:test.output");

                //a straight through proxy
                from("jetty:http://localhost:8091/testJSONAPI")
                        .to("jetty:http://localhost:8091/targetJSONAPI?bridgeEndpoint=true&throwExceptionOnFailure=false");

            }
        };
    }

    //setup some of the Spring details for the Camel 'fake' ESB
    @Override
    public String[] getSpringContextPaths() {
        return new String[]{"FakeESBContext.xml"};
    }

}
