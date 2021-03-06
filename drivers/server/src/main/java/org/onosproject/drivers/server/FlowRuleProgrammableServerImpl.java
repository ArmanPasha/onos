/*
 * Copyright 2018 Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.drivers.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Sets;

import org.slf4j.Logger;

import org.onosproject.drivers.server.devices.nic.NicFlowRule;
import org.onosproject.drivers.server.devices.nic.NicRxFilter.RxFilter;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultFlowEntry;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleProgrammable;
import org.onosproject.net.flow.FlowRuleService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.ws.rs.ProcessingException;

import com.google.common.base.Strings;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages rules on commodity server devices, by
 * converting ONOS FlowRule objetcs into
 * network interface card (NIC) rules and vice versa.
 */
public class FlowRuleProgrammableServerImpl extends BasicServerDriver
        implements FlowRuleProgrammable {

    private final Logger log = getLogger(getClass());

    /**
     * Resource endpoints of the server agent (REST server-side).
     */
    private static final String RULE_MANAGEMENT_URL = BASE_URL + "/rules";

    /**
     * Parameters to be exchanged with the server's agent.
     */
    private static final String PARAM_RULES        = "rules";
    private static final String PARAM_CPU_ID       = "cpuId";
    private static final String PARAM_CPU_RULES    = "cpuRules";
    private static final String PARAM_RULE_ID      = "ruleId";
    private static final String PARAM_RULE_CONTENT = "ruleContent";

    @Override
    public Collection<FlowEntry> getFlowEntries() {
        DeviceId deviceId = getHandler().data().deviceId();
        checkNotNull(deviceId, DEVICE_ID_NULL);

        // Expected FlowEntries installed through ONOS
        FlowRuleService flowService = getHandler().get(FlowRuleService.class);
        Iterable<FlowEntry> flowEntries = flowService.getFlowEntries(deviceId);

        // Hit the path that provides the server's flow rules
        InputStream response = null;
        try {
            response = getController().get(deviceId, RULE_MANAGEMENT_URL, JSON);
        } catch (ProcessingException pEx) {
            log.error("Failed to get flow entries from device: {}", deviceId);
            return Collections.EMPTY_LIST;
        }

        // Load the JSON into objects
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode objNode = null;
        try {
            Map<String, Object> jsonMap  = mapper.readValue(response, Map.class);
            JsonNode jsonNode = mapper.convertValue(jsonMap, JsonNode.class);
            objNode = (ObjectNode) jsonNode;
        } catch (IOException ioEx) {
            log.error("Failed to get flow entries from device: {}", deviceId);
            return Collections.EMPTY_LIST;
        }

        if (objNode == null) {
            log.error("Failed to get flow entries from device: {}", deviceId);
            return Collections.EMPTY_LIST;
        }

        JsonNode scsNode = objNode.path(PARAM_RULES);

        // Here we store the trully installed rules
        Collection<FlowEntry> actualFlowEntries =
            Sets.<FlowEntry>newConcurrentHashSet();

        for (JsonNode scNode : scsNode) {
            String scId = get(scNode, PARAM_ID);
            String rxFilter = get(
                scNode.path(NIC_PARAM_RX_FILTER), NIC_PARAM_RX_METHOD);

            // Only Flow-based RxFilter is permitted
            if (RxFilter.getByName(rxFilter) != RxFilter.FLOW) {
                log.warn("Device with Rx filter {} is not managed by this driver",
                    rxFilter.toString().toUpperCase());
                continue;
            }

            // Each device might have multiple NICs
            for (JsonNode nicNode : scNode.path(PARAM_NICS)) {
                JsonNode cpusNode = nicNode.path(PARAM_CPUS);

                // Each NIC can dispatch to multiple CPU cores
                for (JsonNode cpuNode : cpusNode) {
                    String cpuId = get(cpuNode, PARAM_CPU_ID);
                    JsonNode rulesNode = cpuNode.path(PARAM_CPU_RULES);

                    // Multiple rules might correspond to each CPU core
                    for (JsonNode ruleNode : rulesNode) {
                        long ruleId = ruleNode.path(PARAM_RULE_ID).asLong();
                        String ruleContent = get(ruleNode, PARAM_RULE_CONTENT);

                        // Search for this rule ID in ONOS's store
                        FlowRule r = findRuleInFlowEntries(flowEntries, ruleId);

                        // Local rule, not present in the controller => Ignore
                        if (r == null) {
                            continue;
                        // Rule trully present in the data plane => Add
                        } else {
                            actualFlowEntries.add(
                                new DefaultFlowEntry(
                                    r, FlowEntry.FlowEntryState.ADDED, 0, 0, 0));
                        }
                    }
                }
            }
        }

        return actualFlowEntries;
    }

    @Override
    public Collection<FlowRule> applyFlowRules(Collection<FlowRule> rules) {
        DeviceId deviceId = getHandler().data().deviceId();
        checkNotNull(deviceId, DEVICE_ID_NULL);

        // Set of truly-installed rules to be reported
        Set<FlowRule> installedRules = Sets.<FlowRule>newConcurrentHashSet();

        // Splits the rule set into multiple ones, grouped by traffic class ID
        Map<String, Set<FlowRule>> rulesPerTc = groupRules(rules);

        // Install NIC rules on a per-traffic class basis
        for (Map.Entry<String, Set<FlowRule>> entry : rulesPerTc.entrySet()) {
            String tcId = entry.getKey();
            Set<FlowRule> tcRuleSet = entry.getValue();

            installedRules.addAll(
                installNicFlowRules(deviceId, tcId, tcRuleSet)
            );
        }

        return installedRules;
    }

    @Override
    public Collection<FlowRule> removeFlowRules(Collection<FlowRule> rules) {
        DeviceId deviceId = getHandler().data().deviceId();
        checkNotNull(deviceId, DEVICE_ID_NULL);

        // Set of truly-removed rules to be reported
        Set<FlowRule> removedRules = Sets.<FlowRule>newConcurrentHashSet();

        // for (FlowRule rule : rules) {
        rules.forEach(rule -> {
            if (removeNicFlowRule(deviceId, rule.id().value())) {
                removedRules.add(rule);
            }
        });

        return removedRules;
    }

    /**
     * Groups a set of FlowRules by their traffic class ID.
     *
     * @param rules set of NIC rules to install
     * @return a map of traffic class IDs to their set of NIC rules
     */
    private Map<String, Set<FlowRule>> groupRules(Collection<FlowRule> rules) {
        Map<String, Set<FlowRule>> rulesPerTc =
            new ConcurrentHashMap<String, Set<FlowRule>>();

        rules.forEach(rule -> {
            if (!(rule instanceof FlowEntry)) {
                NicFlowRule nicRule = (NicFlowRule) rule;
                String tcId = nicRule.trafficClassId();

                // Create a bucket of flow rules for this traffic class
                if (!rulesPerTc.containsKey(tcId)) {
                    rulesPerTc.put(tcId, Sets.<FlowRule>newConcurrentHashSet());
                }

                Set<FlowRule> tcRuleSet = rulesPerTc.get(tcId);
                tcRuleSet.add(nicRule);
            }
        });

        return rulesPerTc;
    }

    /**
     * Searches for a flow rule with certain ID.
     *
     * @param flowEntries a list of FlowEntries
     * @param ruleId a desired rule ID
     * @return a FlowRule that corresponds to the desired ID or null
     */
    private FlowRule findRuleInFlowEntries(
            Iterable<FlowEntry> flowEntries, long ruleId) {
        for (FlowEntry fe : flowEntries) {
            if (fe.id().value() == ruleId) {
                return (FlowRule) fe;
            }
        }

        return null;
    }

    /**
     * Installs a set of FlowRules of the same traffic class ID
     * on a server device.
     *
     * @param deviceId target server device ID
     * @param trafficClassId traffic class ID of the NIC rules
     * @param rules set of NIC rules to install
     * @return a set of successfully installed NIC rules
     */
    private Collection<FlowRule> installNicFlowRules(
            DeviceId deviceId, String trafficClassId,
            Collection<FlowRule> rules) {
        if (rules.isEmpty()) {
            return Collections.EMPTY_LIST;
        }

        ObjectMapper mapper = new ObjectMapper();

        // Create the object node to host the list of rules
        ObjectNode scsObjNode = mapper.createObjectNode();

        // Add the service chain's traffic class ID that requested these rules
        scsObjNode.put(BasicServerDriver.PARAM_ID, trafficClassId);

        // Create the object node to host the Rx filter method
        ObjectNode methodObjNode = mapper.createObjectNode();
        methodObjNode.put(BasicServerDriver.NIC_PARAM_RX_METHOD, "flow");
        scsObjNode.put(BasicServerDriver.NIC_PARAM_RX_FILTER, methodObjNode);

        // Map each core to an array of rule IDs and rules
        Map<Long, ArrayNode> cpuObjSet =
            new ConcurrentHashMap<Long, ArrayNode>();

        String nic = null;

        for (FlowRule rule : rules) {
            NicFlowRule nicRule = (NicFlowRule) rule;
            long coreIndex = nicRule.cpuCoreIndex();

            // Keep the ID of the target NIC
            if (nic == null) {
                nic = findNicInterfaceWithPort(deviceId, nicRule.interfaceNumber());
                checkArgument(
                    !Strings.isNullOrEmpty(nic),
                    "Attempted to install rules on an invalid NIC");
            }

            // Create a JSON array for this CPU core
            if (!cpuObjSet.containsKey(coreIndex)) {
                cpuObjSet.put(coreIndex, mapper.createArrayNode());
            }

            // The array of rules that corresponds to this CPU core
            ArrayNode ruleArrayNode = cpuObjSet.get(coreIndex);

            // Each rule has an ID and a content
            ObjectNode ruleNode = mapper.createObjectNode();
            ruleNode.put("ruleId", nicRule.id().value());
            ruleNode.put("ruleContent", nicRule.ruleBody());

            ruleArrayNode.add(ruleNode);
        }

        ObjectNode nicObjNode = mapper.createObjectNode();
        nicObjNode.put("nicName", nic);

        ArrayNode cpusArrayNode = nicObjNode.putArray(PARAM_CPUS);

        // Convert the map of CPU cores to arrays of rules to JSON
        for (Map.Entry<Long, ArrayNode> entry : cpuObjSet.entrySet()) {
            long coreIndex = entry.getKey();
            ArrayNode ruleArrayNode = entry.getValue();

            ObjectNode cpuObjNode = mapper.createObjectNode();
            cpuObjNode.put("cpuId", coreIndex);
            cpuObjNode.putArray(PARAM_CPU_RULES).addAll(ruleArrayNode);

            cpusArrayNode.add(cpuObjNode);
        }

        scsObjNode.putArray(PARAM_NICS).add(nicObjNode);

        // Create the object node to host all the data
        ObjectNode sendObjNode = mapper.createObjectNode();
        sendObjNode.putArray(PARAM_RULES).add(scsObjNode);

        // Post the NIC rules to the server
        int response = getController().post(
            deviceId, RULE_MANAGEMENT_URL,
            new ByteArrayInputStream(sendObjNode.toString().getBytes()), JSON);

        // Upon an error, return an empty set of rules
        if (!checkStatusCode(response)) {
            log.error("Failed to install flow rules on device {}", deviceId);
            return Collections.EMPTY_LIST;
        }

        log.info("Successfully installed {} flow rules on device {}",
            rules.size(), deviceId);

        // .. or all of them
        return rules;
    }

    /**
     * Removes a FlowRule from a server device.
     *
     * @param deviceId target server device ID
     * @param ruleId NIC rule ID to be removed
     * @return boolean removal status
     */
    private boolean removeNicFlowRule(DeviceId deviceId, long ruleId) {
        // Remove rule with ID from this server
        int response = getController().delete(deviceId,
            RULE_MANAGEMENT_URL + "/" + Long.toString(ruleId), null, JSON);

        if (!checkStatusCode(response)) {
            log.error("Failed to remove flow rule {} from device {}",
                ruleId, deviceId);
            return false;
        }

        log.info("Successfully removed flow rule {} from device {}",
            ruleId, deviceId);

        return true;
    }

}
