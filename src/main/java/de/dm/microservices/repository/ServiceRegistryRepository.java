package de.dm.microservices.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.dm.microservices.business.DefaultNodeContentFactory;
import de.dm.microservices.business.MicroserviceDtoFactory;
import de.dm.microservices.business.ServiceRegistryProperties;
import de.dm.microservices.domain.MicroserviceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class ServiceRegistryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceRegistryRepository.class);

    private static final String APPLICATION = "application";
    private static final String NAME = "name";
    private static final String HOSTS = "hosts";
    private static final String METADATA = "metadata";
    private static final String INSTANCE = "instance";
    private static final String HOST_NAME = "hostName";
    private static final String IP_ADDR = "ipAddr";
    private static final String HOME_PAGE_URL = "homePageUrl";
    private static final String PORT = "port";
    private static final String SECURE_PORT = "securePort";
    private static final String PORTS = "ports";
    private static final String CONSUMES = "consumes";
    private static final String BITBUCKET_URL = "bitbucketUrl";
    private static final String IGNORED_COMMITTERS = "ignoredCommitters";
    private static final String DESCRIPTION = "description";
    private static final String AT_ENABLED = "@enabled";
    private static final String TARGET = "target";
    private static final String TYPE = "type";

    private final RestTemplate restTemplate;
    private final DefaultNodeContentFactory defaultNodeContentFactory;
    private final MicroserviceDtoFactory microserviceDtoFactory;
    private final Boolean offlineMode;
    private final Map<String, String> registryUrls;
    private final ObjectMapper mapper;

    @Autowired
    public ServiceRegistryRepository(RestTemplate restTemplate,
                                     DefaultNodeContentFactory defaultNodeContentFactory,
                                     @Value("${development.offline-mode:false}") String offlineMode,
                                     MicroserviceDtoFactory microserviceDtoFactory,
                                     ServiceRegistryProperties serviceRegistryProperties,
                                     ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.defaultNodeContentFactory = defaultNodeContentFactory;
        this.offlineMode = Boolean.parseBoolean(offlineMode);
        this.microserviceDtoFactory = microserviceDtoFactory;
        this.registryUrls = serviceRegistryProperties.getUrl();
        this.mapper = mapper;
    }


    @Cacheable("allmicroservices")
    public Map<String, MicroserviceDto> findAllServices(String stage) {
        LOG.info("Load services from Registry ...");
        final String nodeApplications = "applications";

        if (offlineMode) {
            LOG.info("Dev mode: not fetching microservices from registry, returning empty map...");
            return Collections.emptyMap();
        }

        ResponseEntity<ObjectNode> responseEntity;
        try {
            responseEntity = requestServices(stage);
        } catch (RestClientException ex) {
            LOG.warn("Error fetching microservices from registry, returning empty map...", ex);
            return Collections.emptyMap();
        }

        final ObjectNode response = responseEntity.getBody();
        if (!response.hasNonNull(nodeApplications)) {
            return Collections.emptyMap();
        }

        final JsonNode rootNode = response.get(nodeApplications);
        if (!rootNode.hasNonNull(APPLICATION)) {
            return Collections.emptyMap();
        }

        final ArrayNode applicationsNode = (ArrayNode) rootNode.get(APPLICATION);
        return createResult(applicationsNode);
    }

    public Set<String> getAllStageNames() {
        return this.registryUrls.keySet();
    }

    private Map<String, MicroserviceDto> createResult(ArrayNode applicationsNode) {
        final Map<String, MicroserviceDto> microserviceDtoMap = new HashMap<>();
        applicationsNode.forEach(application -> {
            final String applicationName = application.get(NAME).textValue();
            final ObjectNode applicationNode = defaultNodeContentFactory.create(applicationName);
            applicationNode.set(HOSTS, readHostInfos(application));
            applicationNode.set(CONSUMES, readConsumers(application));
            addMetadataPropertiesToBaseNode(applicationNode, application);
            microserviceDtoMap.put(applicationName, microserviceDtoFactory.getMicroserviceDtoFromJSON(applicationNode.toString()));
        });
        return microserviceDtoMap;
    }


    private ArrayNode readHostInfos(JsonNode applicationNode) {

        final ArrayNode result = JsonNodeFactory.instance.arrayNode();
        final ArrayNode instances = (ArrayNode) applicationNode.get(INSTANCE);
        instances.forEach(instanceNode -> {

            final ObjectNode hostResultNode = mapper.createObjectNode();
            addSingleProperty(hostResultNode, instanceNode, HOST_NAME);
            addSingleProperty(hostResultNode, instanceNode, IP_ADDR);
            addSingleProperty(hostResultNode, instanceNode, HOME_PAGE_URL);

            final ArrayNode portNodes = JsonNodeFactory.instance.arrayNode();
            addArrayProperty(portNodes, instanceNode, PORT);
            addArrayProperty(portNodes, instanceNode, SECURE_PORT);

            hostResultNode.set(PORTS, portNodes);
            result.add(hostResultNode);
        });

        return result;
    }

    private void addMetadataPropertiesToBaseNode(ObjectNode applicationNode, JsonNode application) {
        final ArrayNode instances = (ArrayNode) application.get(INSTANCE);
        final JsonNode metadata = instances.get(0).get(METADATA);

        addChildNodeToBaseNode(applicationNode, metadata, DESCRIPTION);
        addChildNodeToBaseNode(applicationNode, metadata, BITBUCKET_URL);
        addChildNodeToBaseNode(applicationNode, metadata, IGNORED_COMMITTERS);
        addChildNodeToBaseNode(applicationNode, metadata, "fdOwner");
        addChildNodeToBaseNode(applicationNode, metadata, "tags");
        addChildNodeToBaseNode(applicationNode, metadata, "description");
        addChildNodeToBaseNode(applicationNode, metadata, "microserviceUrl");
        addChildNodeToBaseNode(applicationNode, metadata, "ipAddress");
        addChildNodeToBaseNode(applicationNode, metadata, "networkZone");
        addChildNodeToBaseNode(applicationNode, metadata, "documentationLink");
        addChildNodeToBaseNode(applicationNode, metadata, "buildMonitorLink");
        addChildNodeToBaseNode(applicationNode, metadata, "monitoringLink");
    }


    private ArrayNode readConsumers(JsonNode applicationNode) {

        final ArrayNode result = JsonNodeFactory.instance.arrayNode();
        final ArrayNode instances = (ArrayNode) applicationNode.get(INSTANCE);
        final JsonNode metadata = instances.get(0).get(METADATA);

        if (metadata.get(CONSUMES) != null) {

            String consumersInput = metadata.get(CONSUMES).asText();
            String[] consumersWithCommunicationType = consumersInput.split(",");

            for (String consumerWithType : consumersWithCommunicationType) {
                ObjectNode consumerObjectNode = JsonNodeFactory.instance.objectNode();

                String[] consumer = consumerWithType.split(":");
                int consumerLength = consumer.length;

                if (consumerLength >= 1) {
                    consumerObjectNode.put(TARGET, consumer[0].toUpperCase());

                    if (consumerLength == 2) {
                        consumerObjectNode.put(TYPE, consumer[1]);
                    }
                    result.add(consumerObjectNode);
                }
            }
        }
        return result;
    }

    private void addChildNodeToBaseNode(ObjectNode applicationNode, JsonNode metadata, String fieldName) {
        if (metadata.get(fieldName) != null) {
            applicationNode.set(fieldName, metadata.get(fieldName));
        }
    }


    private void addArrayProperty(ArrayNode portsNode, JsonNode instanceNode, String propertyName) {
        if (instanceNode.hasNonNull(propertyName)) {
            final JsonNode port = instanceNode.get(propertyName);
            if ("true".equals(port.get(AT_ENABLED).textValue())) {
                portsNode.add(JsonNodeFactory.instance.numberNode(port.get("$").intValue()));
            }
        }
    }

    private void addSingleProperty(ObjectNode hostNode, JsonNode instanceNode, String propertyName) {
        if (instanceNode.hasNonNull(propertyName)) {
            hostNode.set(propertyName, JsonNodeFactory.instance.textNode(instanceNode.get(propertyName).asText()));
        }
    }

    private ResponseEntity<ObjectNode> requestServices(String stage) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));

        if (!registryUrls.containsKey(stage)) {
            throw new InvalidStageNameException("Invalid stage name \"" + stage + "\"");
        }

        String registryUrl = registryUrls.get(stage);

        final HttpEntity<String> httpEntity = new HttpEntity<>("parameters", headers);
        return restTemplate.exchange(registryUrl, HttpMethod.GET, httpEntity, ObjectNode.class);
    }
}
