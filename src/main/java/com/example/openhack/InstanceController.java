package com.example.openhack;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.AppsV1beta1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.*;
import io.kubernetes.client.util.Config;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

@RestController
public class InstanceController {

    @GetMapping("/original")
    public V1PodList getOriginalInstances() throws IOException, ApiException {
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        CoreV1Api api = new CoreV1Api();
        V1PodList list = api.listPodForAllNamespaces(null, null, null, null, null, null, null, null, null);
        for (V1Pod item : list.getItems()) {
            System.out.println(item.getMetadata().getName());
        }
        return list;
    }

    @GetMapping("/instances")
    public List<Instance> getInstances() throws IOException, ApiException {
        List<Instance> instances = new ArrayList<>();

        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        CoreV1Api api = new CoreV1Api();

        String publicIp = "";

        V1ServiceList services = api.listServiceForAllNamespaces(null, null, null, null, null, null, null, null, null);
        for (V1Service service : services.getItems()) {
            if (!"azure-k8s-minecraft".equalsIgnoreCase(service.getMetadata().getName())) {
                continue;
            }
            publicIp = service.getStatus().getLoadBalancer().getIngress().get(0).getIp();
        }

        // Get the list of all deployments
        AppsV1beta1Api apiInstance = new AppsV1beta1Api();
        AppsV1beta1DeploymentList deploymentList = apiInstance.listDeploymentForAllNamespaces(null, null, null, null, null, null, null, null, null);
        for (AppsV1beta1Deployment item : deploymentList.getItems()) {

            // Filter to keep only the minecraft servers
            if (item.getMetadata().getLabels().get("app") != null && item.getMetadata().getLabels().get("app").startsWith("azure-k8s-minecraft")) {
                String name = item.getMetadata().getName();

                HashMap<String, String> endpoints = new HashMap<>();
                if (item.getSpec().getTemplate().getSpec().getContainers().size() > 0) {
                    item.getSpec().getTemplate().getSpec().getContainers().get(0).getPorts().forEach(v1ContainerPort -> endpoints.put((v1ContainerPort.getName().equalsIgnoreCase("default") ? "minecraft" : v1ContainerPort.getName()), v1ContainerPort.getContainerPort().toString()));
                }
                endpoints.put("minecraft", publicIp + ":" + endpoints.get("minecraft"));
                endpoints.put("rcon", publicIp + ":" + endpoints.get("rcon"));

//                https://mcapi.us/server/status?ip=104.211.53.31

                RestTemplate restTemplate = new RestTemplate();
                URI uri = null;
                try {
                    uri = new URI("https://mcapi.us/server/status?ip=" + publicIp + "&port=" + endpoints.get("minecraft"));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                }
                ResponseEntity<ServerStatus> serverStatusResponse = restTemplate.getForEntity(uri, ServerStatus.class);
                ServerStatus serverStatus = serverStatusResponse.getBody();
                String players = serverStatus.getPlayers().get("now") + "/" + serverStatus.getPlayers().get("max");
//                String status = (serverStatus.getStatus().equalsIgnoreCase("success") ? "UP" : "DOWN");

                instances.add(new Instance(name, endpoints, players));
            }
        }

        // Get the list of all pods
//        V1PodList list = api.listPodForAllNamespaces(null, null, null, null, null, null, null, null, null);
//        for (V1Pod item : list.getItems()) {
//
//            // Filter to keep only the minecraft servers
//            if (item.getMetadata().getLabels().get("app") != null && item.getMetadata().getLabels().get("app").startsWith("azure-k8s-minecraft")) {
//                String name = item.getMetadata().getName();
//
//                HashMap<String, String> endpoints = new HashMap<>();
//                if (item.getSpec().getContainers().size() > 0) {
//                    item.getSpec().getContainers().get(0).getPorts().forEach(v1ContainerPort -> endpoints.put((v1ContainerPort.getName().equalsIgnoreCase("default") ? "minecraft" : v1ContainerPort.getName()), v1ContainerPort.getContainerPort().toString()));
//                }
//                endpoints.put("minecraft", publicIp + ":" + endpoints.get("minecraft"));
//                endpoints.put("rcon", publicIp + ":" + endpoints.get("rcon"));
//
//                instances.add(new Instance(name, endpoints));
//            }
//
//        }
        return instances;
    }

    @PostMapping("/instances")
    public ResponseEntity<Instance> addInstance() throws IOException {

        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        AppsV1beta1Api apiInstance = new AppsV1beta1Api();

        Random rand = new Random();
        int randomPort = rand.nextInt(100) + 25565;

        AppsV1beta1Deployment deployment = new AppsV1beta1Deployment();
        deployment.setApiVersion("apps/v1beta1");
        deployment.setKind("Deployment");
        V1ObjectMeta v1ObjectMetadata = new V1ObjectMeta();
        v1ObjectMetadata.setName("azure-k8s-minecraft-" + randomPort);
        deployment.setMetadata(v1ObjectMetadata);

        AppsV1beta1DeploymentSpec deploymentSpec = new AppsV1beta1DeploymentSpec();
        deploymentSpec.setReplicas(1);

        V1PodTemplateSpec podTemplateSpec = new V1PodTemplateSpec();
        V1ObjectMeta v1ObjectMeta = new V1ObjectMeta();
        Map<String, String> map = new HashMap<>();
        map.put("app", "azure-k8s-minecraft-" + randomPort);
        v1ObjectMeta.setLabels(map);
        podTemplateSpec.setMetadata(v1ObjectMeta);

        V1PodSpec v1PodSpec = new V1PodSpec();

        List<V1Volume> v1Volumes = new ArrayList<>();
        V1Volume v1Volume = new V1Volume();
        v1Volume.setName("task-pv-storage");
        V1PersistentVolumeClaimVolumeSource v1PersistentVolumeClaimVolumeSource = new V1PersistentVolumeClaimVolumeSource();
        v1PersistentVolumeClaimVolumeSource.setClaimName("task-pv-claim");
        v1Volume.setPersistentVolumeClaim(v1PersistentVolumeClaimVolumeSource);
        v1Volumes.add(v1Volume);
        v1PodSpec.setVolumes(v1Volumes);

        List<V1Container> v1Containers = new ArrayList<>();
        V1Container v1Container = new V1Container();
        v1Container.setName("azure-k8s-minecraft-" + randomPort);
        v1Container.setImage("openhack/minecraft-server:2.0");

        List<V1VolumeMount> v1VolumeMounts = new ArrayList<>();
        V1VolumeMount v1VolumeMount = new V1VolumeMount();
        v1VolumeMount.setName("task-pv-storage");
        v1VolumeMount.setMountPath("/mnt/data");
        v1VolumeMounts.add(v1VolumeMount);
        v1Container.setVolumeMounts(v1VolumeMounts);

        List<V1EnvVar> v1EnvVars = new ArrayList<>();
        V1EnvVar v1EnvVar = new V1EnvVar();
        v1EnvVar.setName("EULA");
        v1EnvVar.setValue("TRUE");
        v1EnvVars.add(v1EnvVar);
        v1Container.setEnv(v1EnvVars);

        List<V1ContainerPort> v1ContainerPorts = new ArrayList<>();
        V1ContainerPort minecraftPort = new V1ContainerPort();
        minecraftPort.setName("minecraft");

        minecraftPort.setContainerPort(randomPort);
        v1ContainerPorts.add(minecraftPort);

        V1ContainerPort rconPort = new V1ContainerPort();
        rconPort.setName("rcon");
        rconPort.setContainerPort(25575);
        v1ContainerPorts.add(rconPort);

        v1Container.setPorts(v1ContainerPorts);

        v1Containers.add(v1Container);

        v1PodSpec.setContainers(v1Containers);
        podTemplateSpec.setSpec(v1PodSpec);

        deploymentSpec.setTemplate(podTemplateSpec);

        deployment.setSpec(deploymentSpec);

        try {
            apiInstance.createNamespacedDeployment("default", deployment, "true");
        } catch (ApiException e) {
            e.printStackTrace();
        }

        return null;
    }

    @DeleteMapping("/instances/{instanceId}")
    public ResponseEntity.HeadersBuilder<?> deleteInstance(@PathVariable String instanceId) throws IOException, ApiException {

        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        AppsV1beta1Api apiInstance = new AppsV1beta1Api();
        String name = instanceId; // String | name of the Deployment
        String namespace = "default"; // String | object name and auth scope, such as for teams and projects
        V1DeleteOptions body = new V1DeleteOptions(); // V1DeleteOptions |
        String pretty = "pretty_example"; // String | If 'true', then the output is pretty printed.
        Integer gracePeriodSeconds = 0; // Integer | The duration in seconds before the object should be deleted. Value must be non-negative integer. The value zero indicates delete immediately. If this value is nil, the default grace period for the specified type will be used. Defaults to a per object value if not specified. zero means delete immediately.
        Boolean orphanDependents = true; // Boolean | Deprecated: please use the PropagationPolicy, this field will be deprecated in 1.7. Should the dependent objects be orphaned. If true/false, the \"orphan\" finalizer will be added to/removed from the object's finalizers list. Either this field or PropagationPolicy may be set, but not both.
        String propagationPolicy = "propagationPolicy_example"; // String | Whether and how garbage collection will be performed. Either this field or OrphanDependents may be set, but not both. The default policy is decided by the existing finalizer set in the metadata.finalizers and the resource-specific default policy.
        try {
            V1Status result = apiInstance.deleteNamespacedDeployment(name, namespace, body, pretty, gracePeriodSeconds, orphanDependents, propagationPolicy);
            System.out.println(result);
        } catch (ApiException e) {
            System.err.println("Exception when calling AppsV1beta1Api#deleteNamespacedDeployment");
            e.printStackTrace();
        }
        return ResponseEntity.noContent();
    }
}
