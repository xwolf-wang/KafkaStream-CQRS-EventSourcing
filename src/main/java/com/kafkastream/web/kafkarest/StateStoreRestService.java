package com.kafkastream.web.kafkarest;

import com.kafkastream.dto.CustomerOrderDTO;
import com.kafkastream.model.CustomerOrder;
import com.kafkastream.util.HostStoreInfo;
import com.kafkastream.util.MetadataService;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;


@Path("customer-orders")
public class StateStoreRestService
{
    private final KafkaStreams streams;
    private final MetadataService metadataService;
    private final HostInfo hostInfo;
    private final Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();
    private Server jettyServer;
    private final String    customerOrdersStoreName="customerordersstore";

    public StateStoreRestService(final KafkaStreams streams, final HostInfo hostInfo)
    {
        this.streams = streams;
        this.metadataService = new MetadataService(streams);
        this.hostInfo = hostInfo;
    }

    private static <T> T waitUntilStoreIsQueryable(final String storeName, final QueryableStoreType<T> queryableStoreType, final KafkaStreams streams) throws InterruptedException
    {
        while (true)
        {
            try
            {
                Collection<StreamsMetadata> streamsMetadataCollection = streams.allMetadata();
                Iterator<StreamsMetadata> streamsMetadataIterator = streamsMetadataCollection.iterator();
                while (streamsMetadataIterator.hasNext())
                {
                    System.out.println("streamsMetadataIterator.next() -> " + streamsMetadataIterator.next());
                }
                return streams.store(storeName, queryableStoreType);
            } catch (InvalidStateStoreException ignored)
            {
                // store not yet ready for querying
                Thread.sleep(100);
            }
        }
    }

    @GET
    @Path("/{customerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<CustomerOrderDTO> getCustomerOrders(@PathParam("customerId") String customerId) throws InterruptedException
    {
        System.out.println("Inside getCustomerOrders()");
        /* final HostStoreInfo host = metadataService.streamsMetadataForStoreAndKey("customerordersstore", "all", new StringSerializer()); */
        List<CustomerOrderDTO> customerOrderList = new ArrayList<>();
        ReadOnlyKeyValueStore<String, CustomerOrder> customerOrdersStore = waitUntilStoreIsQueryable(this.customerOrdersStoreName, QueryableStoreTypes.keyValueStore(), streams);
        KeyValueIterator<String, CustomerOrder> keyValueIterator = customerOrdersStore.all();
        while (keyValueIterator.hasNext())
        {
            KeyValue<String, CustomerOrder> customerOrderKeyValue = keyValueIterator.next();
            if (customerOrderKeyValue.value.getCustomerId().toString().equals(customerId))
            {
                customerOrderList.add(getCustomerOrderDTOFromCustomerOrder(customerOrderKeyValue.value));
            }

        }
        return customerOrderList;
    }

    @GET
    @Path("/all")
    @Produces(MediaType.APPLICATION_JSON)
    public List<CustomerOrderDTO> getAllCustomersOrders() throws InterruptedException
    {
        System.out.println("Inside getAllCustomersOrders()");
        final HostStoreInfo host = metadataService.streamsMetadataForStoreAndKey(this.customerOrdersStoreName, "all", new StringSerializer());
        // Customer Orders view is hosted on another instance
     /*   if (!thisHost(host))
        {
            return client.target(String.format("http://%s:%d/%s", host.getHost(), host.getPort(), "/customer-orders/all"))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .get(new GenericType<List<CustomerOrder>>(){});
        }*/

        List<CustomerOrderDTO> customerOrderList = new ArrayList<>();
        ReadOnlyKeyValueStore<String, CustomerOrder> customerOrdersStore = waitUntilStoreIsQueryable(this.customerOrdersStoreName, QueryableStoreTypes.keyValueStore(), streams);
        KeyValueIterator<String, CustomerOrder> keyValueIterator = customerOrdersStore.all();
        while (keyValueIterator.hasNext())
        {
            KeyValue<String, CustomerOrder> customerOrderKeyValue = keyValueIterator.next();
            customerOrderList.add(getCustomerOrderDTOFromCustomerOrder(customerOrderKeyValue.value));
        }
        return customerOrderList;
    }

    @GET()
    @Path("/instances")
    @Produces(MediaType.APPLICATION_JSON)
    public List<HostStoreInfo> streamsMetadata()
    {
        return metadataService.streamsMetadata();
    }

    @GET()
    @Path("/instances/{storeName}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<HostStoreInfo> streamsMetadataForStore(@PathParam("storeName") String store)
    {
        return metadataService.streamsMetadataForStore(store);
    }

    private CustomerOrderDTO getCustomerOrderDTOFromCustomerOrder(CustomerOrder customerOrder)
    {
        return new CustomerOrderDTO(customerOrder.getCustomerId().toString(), customerOrder.getFirstName().toString(), customerOrder.getLastName().toString(), customerOrder.getEmail().toString(), customerOrder.getPhone().toString(), customerOrder.getOrderId().toString(), customerOrder.getOrderItemName().toString(), customerOrder.getOrderPlace().toString(), customerOrder.getOrderPurchaseTime().toString());
    }

    private boolean thisHost(final HostStoreInfo host)
    {
        return host.getHost().equals(hostInfo.host()) && host.getPort() == hostInfo.port();
    }

    public void start() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        jettyServer = new Server(hostInfo.port());
        jettyServer.setHandler(context);

        ResourceConfig rc = new ResourceConfig();
        rc.register(this);
        rc.register(JacksonFeature.class);

        ServletContainer sc = new ServletContainer(rc);
        ServletHolder holder = new ServletHolder(sc);
        context.addServlet(holder, "/*");

        jettyServer.start();
    }

    void stop() throws Exception
    {
        if (jettyServer != null)
        {
            jettyServer.stop();
        }
    }

}
