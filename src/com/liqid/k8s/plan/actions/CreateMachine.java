/**
 * k8s-integration
 * Copyright 2023 by Liqid, Inc - All Rights Reserved
 */

package com.liqid.k8s.plan.actions;

import com.liqid.k8s.exceptions.InternalErrorException;
import com.liqid.k8s.plan.ExecutionContext;
import com.liqid.sdk.LiqidException;

/**
 * Creates a machine with the specified name, in the Liqid Cluster
 */
public class CreateMachine extends Action {

    private String _groupName;
    private String _machineName;

    public CreateMachine() {
        super(ActionType.CREATE_MACHINE);
    }

    public CreateMachine setGroupName(final String value) { _groupName = value; return this; }
    public CreateMachine setMachineName(final String value) { _machineName = value; return this; }

    @Override
    public void checkParameters() throws InternalErrorException {
        checkForNull("GroupName", _groupName);
        checkForNull("MachineName", _machineName);
    }

    @Override
    public void perform(
        final ExecutionContext context
    ) throws LiqidException {
        var fn = this.getClass().getName() + ":perform";
        context.getLogger().trace("Entering %s", fn);

        var group = context.getLiqidInventory()._groupsByName.get(_groupName);
        if (group == null) {
            System.out.printf("INFO:Group %s does not exist in the Liqid Cluster\n", _groupName);
            context.getLogger().trace("%s returning", fn);
            return;
        }

        var machine = context.getLiqidClient().createMachine(group.getGroupId(), _machineName);
        context.getLiqidInventory().notifyMachineCreated(machine);

        context.getLogger().trace("%s returning", fn);
    }

    @Override
    public String toString() {
        return String.format("Create Machine %s in Group %s in the Liqid Cluster", _machineName, _groupName);
    }
}
