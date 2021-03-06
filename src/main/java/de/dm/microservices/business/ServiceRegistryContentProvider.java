package de.dm.microservices.business;

import de.dm.microservices.domain.MicroserviceDto;
import de.dm.microservices.repository.ServiceRegistryRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class ServiceRegistryContentProvider {

    private final ServiceRegistryRepository serviceRegistryRepository;

    public ServiceRegistryContentProvider(ServiceRegistryRepository serviceRegistryRepository) {
        this.serviceRegistryRepository = serviceRegistryRepository;
    }

    public Map<String, MicroserviceDto> getAllMicroservices(String stage) {
        return serviceRegistryRepository.findAllServices(stage);
    }

    public Set<String> getAllStageNames() {
        return serviceRegistryRepository.getAllStageNames();
    }

}
