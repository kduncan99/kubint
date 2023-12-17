/**
 * k8s-integration
 * Copyright 2023 by Liqid, Inc - All Rights Reserved
 */

package com.liqid.k8s;

import com.bearsnake.k8sclient.ConfigMapPayload;
import com.bearsnake.k8sclient.K8SClient;
import com.bearsnake.k8sclient.K8SHTTPError;
import com.bearsnake.k8sclient.K8SJSONError;
import com.bearsnake.k8sclient.K8SRequestError;
import com.bearsnake.k8sclient.NamespacedMetadata;
import com.bearsnake.k8sclient.Node;
import com.bearsnake.k8sclient.SecretPayload;
import com.bearsnake.klog.Logger;
import com.bearsnake.klog.StdErrWriter;
import com.bearsnake.klog.StdOutWriter;
import com.liqid.k8s.exceptions.ConfigurationDataException;
import com.liqid.k8s.exceptions.ConfigurationException;
import com.liqid.k8s.exceptions.InternalErrorException;
import com.liqid.k8s.plan.LiqidInventory;
import com.liqid.sdk.DeviceStatus;
import com.liqid.sdk.Group;
import com.liqid.sdk.LiqidClient;
import com.liqid.sdk.LiqidClientBuilder;
import com.liqid.sdk.LiqidException;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.liqid.k8s.Constants.K8S_ANNOTATION_FPGA_ENTRY;
import static com.liqid.k8s.Constants.K8S_ANNOTATION_GPU_ENTRY;
import static com.liqid.k8s.Constants.K8S_ANNOTATION_LINK_ENTRY;
import static com.liqid.k8s.Constants.K8S_ANNOTATION_MEMORY_ENTRY;
import static com.liqid.k8s.Constants.K8S_ANNOTATION_PREFIX;
import static com.liqid.k8s.Constants.K8S_ANNOTATION_SSD_ENTRY;
import static com.liqid.k8s.Constants.K8S_CONFIG_MAP_GROUP_NAME_KEY;
import static com.liqid.k8s.Constants.K8S_CONFIG_MAP_IP_ADDRESS_KEY;
import static com.liqid.k8s.Constants.K8S_CONFIG_NAME;
import static com.liqid.k8s.Constants.K8S_CONFIG_NAMESPACE;
import static com.liqid.k8s.Constants.K8S_SECRET_CREDENTIALS_KEY;
import static com.liqid.k8s.Constants.K8S_SECRET_NAME;
import static com.liqid.k8s.Constants.K8S_SECRET_NAMESPACE;
import static com.liqid.k8s.Constants.LIQID_SDK_LABEL;
import static com.liqid.k8s.annotate.Application.LOGGER_NAME;

/**
 * Abstract class for all command handlers
 */
public abstract class Command {

    protected static final Map<GeneralType, String> ANNOTATION_KEY_FOR_DEVICE_TYPE = new HashMap<>();
    static {
        ANNOTATION_KEY_FOR_DEVICE_TYPE.put(GeneralType.FPGA, K8S_ANNOTATION_FPGA_ENTRY);
        ANNOTATION_KEY_FOR_DEVICE_TYPE.put(GeneralType.GPU, K8S_ANNOTATION_GPU_ENTRY);
        ANNOTATION_KEY_FOR_DEVICE_TYPE.put(GeneralType.MEMORY, K8S_ANNOTATION_MEMORY_ENTRY);
        ANNOTATION_KEY_FOR_DEVICE_TYPE.put(GeneralType.LINK, K8S_ANNOTATION_LINK_ENTRY);
        ANNOTATION_KEY_FOR_DEVICE_TYPE.put(GeneralType.SSD, K8S_ANNOTATION_SSD_ENTRY);
    }

    protected final Logger _logger;
    protected final String _proxyURL;
    protected final Boolean _force;
    protected final Integer _timeoutInSeconds;

    protected String _liqidAddress;
    protected String _liqidGroupName;
    protected String _liqidPassword;
    protected String _liqidUsername;

    protected K8SClient _k8sClient;
    protected LiqidClient _liqidClient;
    protected LiqidInventory _liqidInventory;

    protected Command(
        final Logger logger,
        final String proxyURL,
        final Boolean force,
        final Integer timeoutInSeconds
    ) {
        _logger = logger;
        _proxyURL = proxyURL;
        _force = force;
        _timeoutInSeconds = timeoutInSeconds;
    }

    /**
     * Instructs the subclass to process itself given sufficient information
     * @return true if the command processed successfully, false if it did not
     * @throws ConfigurationException Indicates an inconsistency in the general configuration of the Liqid Cluster
     *                                  or the Kubernetes Cluster
     * @throws ConfigurationDataException Indicates a problem in the actual liqid-supplied data stored in the
     *                                      Liqid Cluster or the Kubernetes Cluster
     * @throws InternalErrorException Indicates that some error has been detected which is likely caused by an error
     *                                  in programming
     * @throws K8SHTTPError Indicates that the Kubernetes API returned an HTTP status which we did not expect
     * @throws K8SJSONError Indicates that the Kubernetes API returned information in a format which we did not expect
     * @throws K8SRequestError Indicates some problem was encountered by the Kubernetes API library - this could be
     *                          a programming problem, or it could be something wrong in the Kubernetes API.
     * @throws LiqidException Indicates that a general error occurred while interacting with the Liqid Director API.
     */
    public abstract boolean process(
    ) throws ConfigurationException,
             ConfigurationDataException,
             InternalErrorException,
             K8SHTTPError,
             K8SJSONError,
             K8SRequestError,
             LiqidException;

    /**
     * This is invoked in situations where such annotations should not exist.
     * We checks all the worker nodes in the k8s cluster to see if any of them have any liqid-related annotations.
     * If any annotations exist, we do the following:
     *      If we are not forcing, we display the list of offending worker nodes and return false.
     *          This is considered an error and the invoker should not proceed with processing.
     *      If we are forcing, we note the existence of annotations on the nodes as warnings,
     *          delete the annotations, then return true. The invoker may continue processing.
     * @param command The command which was issued - this is merely for nice-formatting the error/warning messages.
     * @return true if the invoker may continue, false if processing should stop.
     * @throws K8SHTTPError If any unexpected HTTP responses are received. Generally, we expect only 200s.
     * @throws K8SJSONError If information received from k8s cannot be converted from JSON into the expected data
     *                          structs. This generally indicates a programming error on our part, but it could also
     *                          result from gratuitous changes in k8s, which does unfortunately occur.
     * @throws K8SRequestError Indicates some other error during processing, from within the k8sClient module.
     */
    protected boolean checkForExistingAnnotations(
        final String command
    ) throws K8SHTTPError, K8SJSONError, K8SRequestError {
        var fn = "checkForExistingAnnotations";
        _logger.trace("Entering %s", fn);

        // Are there any annotations? If so, we note them, and either quit or clear them depending on _force flag.
        var workersWithAnnotations = new LinkedList<Node>();
        var nodeEntities = _k8sClient.getNodes();
        for (var node : nodeEntities) {
            var annos = _k8sClient.getAnnotationsForNode(node.getName());
            for (var key : annos.keySet()) {
                if (key.startsWith(K8S_ANNOTATION_PREFIX)) {
                    workersWithAnnotations.add(node);
                    break;
                }
            }
        }

        var result = true;
        if (!workersWithAnnotations.isEmpty()) {
            var prefix1 = _force ? "WARNING" : "ERROR";
            var prefix2 = _force ? "       " : "     ";
            System.err.printf("%s:The following worker nodes have Liqid Cluster-related annotations:\n", prefix1);
            var names = workersWithAnnotations.stream().map(Node::getName).collect(Collectors.toCollection(LinkedList::new));
            System.err.printf("%s:  %s\n", prefix2, String.join(", ", names));
            if (!_force) {
                System.err.printf("%s:%s command will not be performed unless forced.\n", prefix2, command);
                result = false;
            } else {
                System.err.printf("%s:The nodes will be un-annotated.\n", prefix2);
                for (var node : workersWithAnnotations) {
                    removeAnnotationsFromNode(node.getName());
                }
            }
        }

        _logger.trace("Exiting %s with %s", fn, result);
        return result;
    }

    protected String createAnnotationKeyFor(
        final String keySuffix
    ) {
        return String.format("%s/%s", K8S_ANNOTATION_PREFIX, keySuffix);
    }

    protected String createAnnotationKeyForDeviceType(
        final GeneralType genType
    ) {
        return createAnnotationKeyFor(ANNOTATION_KEY_FOR_DEVICE_TYPE.get(genType));
    }

    /**
     * Creates linkage between Kubernetes and Liqid.
     * Deletes any existing configMap and secret, then creates new configMap and optionally a secet.
     */
    protected void createLinkage(
    ) throws K8SHTTPError, K8SRequestError {
        try {
            _k8sClient.deleteConfigMap(K8S_CONFIG_NAMESPACE, K8S_CONFIG_NAME);
        } catch (K8SHTTPError ex) {
            if (ex.getResponseCode() != 404) {
                throw ex;
            }
        }

        try {
            _k8sClient.deleteSecret(K8S_SECRET_NAMESPACE, K8S_SECRET_NAME);
        } catch (K8SHTTPError ex) {
            if (ex.getResponseCode() != 404) {
                throw ex;
            }
        }

        // Write the configMap
        var cfgMapData = new HashMap<String, String>();
        cfgMapData.put(K8S_CONFIG_MAP_IP_ADDRESS_KEY, _liqidAddress);
        cfgMapData.put(K8S_CONFIG_MAP_GROUP_NAME_KEY, _liqidGroupName);
        var cmMetadata = new NamespacedMetadata().setNamespace(K8S_CONFIG_NAMESPACE).setName(K8S_CONFIG_NAME);
        var newCfgMap = new ConfigMapPayload().setMetadata(cmMetadata).setData(cfgMapData);
        _k8sClient.createConfigMap(newCfgMap);

        // If there are credentials, write a secret
        if (_liqidUsername != null) {
            var mangled = new Credentials(_liqidUsername, _liqidPassword).getMangledString();
            var secretData = Collections.singletonMap(K8S_SECRET_CREDENTIALS_KEY, mangled);
            var secretMetadata = new NamespacedMetadata().setNamespace(K8S_SECRET_NAMESPACE).setName(K8S_SECRET_NAME);
            var newSecret = new SecretPayload().setMetadata(secretMetadata).setData(secretData);
            _k8sClient.createSecret(newSecret);
        }
    }

    /**
     * Creates a new Logger based on our current logger, which does NOT log to stdout or stderr.
     */
    private Logger createSubLogger(
        final String name
    ) {
        var newLogger = new Logger(name, _logger);
        for (var w : newLogger.getWriters()) {
            if ((w instanceof StdOutWriter) || (w instanceof StdErrWriter)) {
                newLogger.removeWriter(w);
            }
        }
        return newLogger;
    }

    /**
     * Displays devices based on the current known liqid inventory (see getLiqidInventory())
     * @param group Reference to Group if we want to limit the display to resources in that group, else null
     */
    protected void displayDevices(
        final Group group
    ) {
        System.out.println();
        if (group == null) {
            System.out.println("All Resources:");
            System.out.println("  ---TYPE---  --NAME--  ----ID----  --------VENDOR--------  -----MODEL------  "
                                   + "-------MACHINE--------  -------------GROUP--------------  --DESCRIPTION--");
        } else {
            System.out.printf("Resources for group '%s':\n", group.getGroupName());
            System.out.println("  ---TYPE---  --NAME--  ----ID----  --------VENDOR--------  -----MODEL------  "
                                   + "-------MACHINE--------  --DESCRIPTION--");
        }

        for (var ds : _liqidInventory._deviceStatusByName.values()) {
            var di = _liqidInventory._deviceInfoById.get(ds.getDeviceId());
            var str1 = String.format("%-10s  %-8s  0x%08x  %-22s  %-16s",
                                     ds.getDeviceType(),
                                     ds.getName(),
                                     ds.getDeviceId(),
                                     di.getVendor(),
                                     di.getModel());

            var dr = _liqidInventory._deviceRelationsByDeviceId.get(ds.getDeviceId());
            var machStr = "<none>";
            if (dr._machineId != null) {
                machStr = _liqidInventory._machinesById.get(dr._machineId).getMachineName();
            }

            var grpStr = "";
            if (group == null) {
                var temp = (dr._groupId == null)
                    ? "<none>"
                    : _liqidInventory._groupsById.get(dr._groupId).getGroupName();
                grpStr = String.format("  %-32s", temp);
            }

            System.out.printf("  %s  %-22s%s  %s\n", str1, machStr, grpStr, di.getUserDescription());
        }
    }

    /**
     * Displays machines based on the current known liqid inventory (see getLiqidInventory())
     * @param group Reference to Group if we want to limit the display to machines in that group, else null
     */
    protected void displayMachines(
        final Group group
    ) {
        System.out.println();
        if (group == null) {
            System.out.println("All Machines:");
            System.out.println("  -------------GROUP--------------  -------MACHINE--------  ----ID----  --------DEVICES---------");
        } else {
            System.out.printf("Machines for group '%s':\n", group.getGroupName());
            System.out.println("  -------MACHINE--------  ----ID----  --------DEVICES---------");
        }

        for (var mach : _liqidInventory._machinesById.values()) {
            var devNames = _liqidInventory._deviceStatusByMachineId.get(mach.getMachineId())
                                                   .stream()
                                                   .map(DeviceStatus::getName)
                                                   .collect(Collectors.toCollection(TreeSet::new));
            var devNamesStr = String.join(" ", devNames);

            if (group == null) {
                var grp = _liqidInventory._groupsById.get(mach.getGroupId());
                System.out.printf("  %-32s  %-22s  0x%08x  %s\n",
                                  grp.getGroupName(),
                                  mach.getMachineName(),
                                  mach.getMachineId(),
                                  devNamesStr);
            } else {
                System.out.printf("  %-22s  0x%08x  %s\n",
                                  mach.getMachineName(),
                                  mach.getMachineId(),
                                  devNamesStr);
            }
        }
    }

    /**
     * Given two sets of the same type, we populate a third set of that type with
     * only those items which are contained in both of the original sets.
     * @param set1 first contributing set
     * @param set2 second contributing set
     * @param intersection result set.
     * @param <T> item type
     */
    protected <T> void getIntersection(
        final Collection<T> set1,
        final Collection<T> set2,
        final LinkedList<T> intersection
    ) {
        intersection.clear();
        set1.stream().filter(set2::contains).forEach(intersection::add);
    }

    /**
     * This code solicits the information we need to interact with the Liqid Cluster from the k8s database.
     * It presumes that the k8s cluster is suitably linked to a Liqid Cluster.
     * Such linkage exists in the form of a ConfigMap and an optional Secret.
     * The specific bits of information returned include:
     *      IP address of the Liqid Cluster (actually, of the director)
     *      Group name of the Liqid Cluster group to which all relevant resources do, or should, belong.
     *      Username credential if basic authentication is enabled for the Liqid cluster
     *      Password credential if basic authentication is enabled for the Liqid cluster,
     *          although we do account for the possibility of a null password for a sadly unprotected username.
     * @return true if we successfully obtained the linkage information
     * @throws ConfigurationDataException Indicates something is wrong in the actual bits of information stored in
     *                                      the configmap or the secret.
     * @throws K8SHTTPError If any unexpected HTTP responses are received. Generally, we expect only 200s.
     * @throws K8SJSONError If information received from k8s cannot be converted from JSON into the expected data
     *                          structs. This generally indicates a programming error on our part, but it could also
     *                          result from gratuitous changes in k8s, which does unfortunately occur.
     * @throws K8SRequestError Indicates some other error during processing, from within the k8sClient module.
     */
    protected boolean getLiqidLinkage(
    ) throws ConfigurationDataException, K8SHTTPError, K8SJSONError, K8SRequestError {
        var fn = "getLiqidLinkage";
        _logger.trace("Entering %s", fn);

        try {
            var cfgMap = _k8sClient.getConfigMap(K8S_CONFIG_NAMESPACE, K8S_CONFIG_NAME);
            _liqidAddress = cfgMap.data.get(K8S_CONFIG_MAP_IP_ADDRESS_KEY);
            _liqidGroupName = cfgMap.data.get(K8S_CONFIG_MAP_GROUP_NAME_KEY);
        } catch (K8SHTTPError ex) {
            if (ex.getResponseCode() == 404) {
                //  We will get a 404 if there is no linkage
                System.err.println("ERROR:No linkage configured for this Kubernetes Cluster");
                var result = false;
                _logger.trace("Exiting %s with %s", fn, result);
                return result;
            } else {
                //  Anything else is a legitimate problem.
                _logger.throwing(ex);
                throw ex;
            }
        }

        try {
            var secret = _k8sClient.getSecret(K8S_SECRET_NAMESPACE, K8S_SECRET_NAME);
            var creds = new Credentials(secret.data.get(K8S_SECRET_CREDENTIALS_KEY));
            _liqidUsername = creds.getUsername();
            _liqidPassword = creds.getPassword();
        } catch (K8SHTTPError ex) {
            //  A 404 could indicate no linkage, but we wouldn't be here in that case.
            //  Thus, if we get here *now* with a 404, it simply means no credentials are configured,
            //  and that might be correct, so we just continue.
            //  Anything else is a legitimate problem.
            if (ex.getResponseCode() != 404) {
                _logger.throwing(ex);
                throw ex;
            }
        }

        var result = true;
        _logger.trace("Exiting %s with %s", fn, result);
        return result;
    }

    /**
     * Initializes a k8sClient object and stores the reference in our local attribute for the client.
     * @return true if we are successful, false otherwise
     */
    protected boolean initK8sClient() {
        var fn = "initK8sClient";
        _logger.trace("Entering %s", fn);

        var result = true;
        try {
            _k8sClient = new K8SClient(_proxyURL, createSubLogger(LOGGER_NAME));
        } catch (IOException ex) {
            _logger.catching(ex);
            System.err.println("ERROR:An error occurred while setting up communication with the Kubernetes cluster");
            System.err.println("     :" + ex);
            result = false;
        }

        _logger.trace("Exiting %s with %s", fn, result);
        return result;
    }

    /**
     * Initializes a LiqidClient object and stores the reference in our local attribute for the client.
     * @return true if we are successful, false otherwise
     */
    protected boolean initLiqidClient() {
        var fn = "initLiqidClient";
        _logger.trace("Entering %s", fn);

        var result = true;
        try {
            _liqidClient = new LiqidClientBuilder().setHostAddress(_liqidAddress)
                                                   .setTimeoutInSeconds(_timeoutInSeconds)
                                                   .build();
            _liqidClient.setLogger(createSubLogger("LiqidSDK"));
            if (_liqidUsername != null) {
                _liqidClient.login(LIQID_SDK_LABEL, _liqidUsername, _liqidPassword);
            }
        } catch (LiqidException ex) {
            // This is not good; however, certain client scenarios might be allowed to proceed anyway if
            // force is set, so we just log the problem and return false and let the caller figure it out.
            _logger.catching(ex);
            result = false;
        }

        _logger.trace("Exiting %s with %s", fn, result);
        return result;
    }

    /**
     * Logs out from the Liqid Cluster *if* we have a LiqidClient established which is logged in.
     * Does nothing otherwise.
     */
    protected void logoutFromLiqidCluster() {
        if ((_liqidClient != null) && (_liqidClient.isLoggedIn())) {
            try {
                _liqidClient.logout();
            } catch (LiqidException ex) {
                _logger.catching(ex);
                System.err.println("WARNING:Failed to logout of Liqid Cluster:" + ex);
            }
        }
    }

    /**
     * Removes all liqid-related annotations from a particular node.
     * @param nodeName name of the worker node
     * @return true if we removed any annotations, else false
     */
    protected boolean removeAnnotationsFromNode(
        final String nodeName
    ) throws K8SHTTPError, K8SJSONError, K8SRequestError {
        var fn = "removeAnnotationsFromNode";
        _logger.trace("Entering %s", fn);

        var annotations = _k8sClient.getAnnotationsForNode(nodeName);
        var changed = false;
        for (java.util.Map.Entry<String, String> entry : annotations.entrySet()) {
            if (entry.getKey().startsWith(K8S_ANNOTATION_PREFIX)) {
                annotations.put(entry.getKey(), null);
                changed = true;
            }
        }
        if (changed) {
            System.out.println("Removing annotations for worker '" + nodeName + "'...");
            _k8sClient.updateAnnotationsForNode(nodeName, annotations);
        }

        _logger.trace("Exiting %s with %s", fn, changed);
        return changed;
    }
}
