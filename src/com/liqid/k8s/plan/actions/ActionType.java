/**
 * k8s-integration
 * Copyright 2023 by Liqid, Inc - All Rights Reserved
 */

package com.liqid.k8s.plan.actions;

public enum ActionType {
    ANNOTATE_NODE,
    CLEAR_CONFIGURATION,
    CREATE_GROUP,
    CREATE_LINKAGE,
    DELETE_GROUP,
    CREATE_MACHINE,
    DELETE_MACHINE,
    ASSIGN_RESOURCES_TO_GROUP,
    ASSIGN_RESOURCES_TO_MACHINE,
    REMOVE_ALL_ANNOTATIONS,
    REMOVE_ANNOTATIONS,
    REMOVE_LINKAGE,
    REMOVE_RESOURCES_FROM_GROUP,
    REMOVE_RESOURCES_FROM_MACHINE,
    SET_USER_DESCRIPTION,
}
