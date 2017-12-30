/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.audit.catalog.CatalogManager;
import org.apache.logging.log4j.audit.exception.AuditException;
import org.apache.logging.log4j.catalog.api.Attribute;
import org.apache.logging.log4j.catalog.api.Constraint;
import org.apache.logging.log4j.catalog.api.Event;
import org.apache.logging.log4j.catalog.api.EventAttribute;
import org.apache.logging.log4j.catalog.api.plugins.ConstraintPlugins;
import org.apache.logging.log4j.message.StructuredDataMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.logging.log4j.catalog.api.constant.Constants.*;

/**
 *
 */
public abstract class AbstractEventLogger {

    private static final Logger logger = LogManager.getLogger(AbstractEventLogger.class);

    private static final int DEFAULT_MAX_LENGTH = 32;

    private static ConstraintPlugins constraintPlugins = ConstraintPlugins.getInstance();

    public CatalogManager catalogManager;

    private static final AuditExceptionHandler DEFAULT_EXCEPTION_HANDLER = (message, ex) -> {
        throw new AuditException("Error logging event " + message.getId().getName(), ex);
    };

    private static final AuditExceptionHandler NOOP_EXCEPTION_HANDLER = (message, ex) -> {
    };

    private AuditExceptionHandler defaultAuditExceptionHandler = DEFAULT_EXCEPTION_HANDLER;

    private final int maxLength;

    protected AbstractEventLogger() {
        maxLength = DEFAULT_MAX_LENGTH;
    }

    protected AbstractEventLogger(int maxLength) {
        this.maxLength = maxLength;
    }

    public void setCatalogManager(CatalogManager catalogManager) {
        this.catalogManager = catalogManager;
    }

    public List<String> getAttributeNames(String eventId) {
        return catalogManager.getAttributeNames(eventId);
    }

    public void setDefaultAuditExceptionHandler(AuditExceptionHandler auditExceptionHandler) {
        defaultAuditExceptionHandler = auditExceptionHandler == null ? NOOP_EXCEPTION_HANDLER : auditExceptionHandler;
    }

    public void logEvent(String eventName, Map<String, String> attributes) {
        Event event = catalogManager.getEvent(eventName);
        if (event == null) {
            throw new AuditException("Unable to locate definition of audit event " + eventName);
        }
        logEvent(eventName, attributes, event, defaultAuditExceptionHandler);
    }

    public void logEvent(String eventName, String catalogId, Map<String, String> attributes) {
        Event event = catalogManager.getEvent(eventName, catalogId);
        if (event == null) {
            throw new AuditException("Unable to locate definition of audit event " + eventName);
        }
        logEvent(eventName, attributes, event, defaultAuditExceptionHandler);
    }

    public void logEvent(String eventName, Map<String, String> attributes, AuditExceptionHandler exceptionHandler) {
        Event event = catalogManager.getEvent(eventName);

        if (event == null) {
            throw new AuditException("Unable to locate definition of audit event " + eventName);
        }
        logEvent(eventName, attributes, event, exceptionHandler);
    }

    protected abstract void logEvent(StructuredDataMessage message);

    private void logEvent(String eventName, Map<String, String> attributes, Event event,
                          AuditExceptionHandler exceptionHandler) {
        AuditMessage msg = new AuditMessage(eventName, maxLength);

        StringBuilder missingAttributes = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        for (EventAttribute eventAttribute : event.getAttributes()) {
            Attribute attr = catalogManager.getAttribute(eventAttribute.getName());
            if (!attr.isRequestContext() && (attr.isRequired() || eventAttribute.isRequired())) {
                String name = attr.getName();
                if (!attributes.containsKey(name)) {
                    if (missingAttributes.length() > 0) {
                        missingAttributes.append(", ");
                    }
                    missingAttributes.append(name);
                } else {
                    if (attr.getConstraints() != null && attr.getConstraints().size() > 0) {
                        Constraint[] constraints = attr.getConstraints().toArray(new Constraint[attr.getConstraints().size()]);
                        validateConstraints(false, constraints, name, attributes.get(name), errors);
                    }
                }
            }
        }
        Map<String, Attribute> attributeMap = catalogManager.getAttributes(eventName, event.getCatalogId());
        for (String name : attributes.keySet()) {
            if (!attributeMap.containsKey(name) && !name.equals("completionStatus")) {
                if (errors.length() > 0) {
                    errors.append("\n");
                }
                errors.append("Attribute ").append(name).append(" is not defined for ").append(eventName);
            }
        }
        if (missingAttributes.length() > 0) {
            if (errors.length() > 0) {
                errors.append("\n");
            }
            errors.append("Event ").append(eventName).append(" is missing required attribute(s) ").append(missingAttributes.toString());
        }
        if (errors.length() > 0) {
            throw new AuditException(errors.toString());
        }
        List<String> attributeNames = catalogManager.getAttributeNames(eventName, event.getCatalogId());
        StringBuilder buf = new StringBuilder();
        for (String attribute : attributes.keySet()) {
            if (!attributeNames.contains(attribute)) {
                if (buf.length() > 0) {
                    buf.append(", ");
                }
                buf.append(attribute);
            }
        }
        if (buf.length() > 0) {
            throw new AuditException("Event " + eventName + " contains invalid attribute(s) " + buf.toString());
        }

        List<String> reqCtxAttrs = catalogManager.getRequiredContextAttributes(eventName, event.getCatalogId());

        if (reqCtxAttrs != null) {
            StringBuilder sb = new StringBuilder();
            for (String attr : reqCtxAttrs) {
                if (!ThreadContext.containsKey(attr)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(attr);
                }
            }
            if (sb.length() > 0) {
                throw new IllegalStateException("Event " + msg.getId().getName() +
                        " is missing required RequestContext values for " + sb.toString());
            }
        }
        Map<String, Attribute> reqCtxAttributes = catalogManager.getRequestContextAttributes();
        for (Map.Entry<String, String> entry : ThreadContext.getImmutableContext().entrySet()) {
            Attribute attribute = reqCtxAttributes.get(entry.getKey());
            if (attribute == null) {
                continue;
            }
            Set<Constraint> constraintList = attribute.getConstraints();
            if (constraintList != null && constraintList.size() > 0) {
                Constraint[] constraints =
                        attribute.getConstraints().toArray(new Constraint[attribute.getConstraints().size()]);
                validateConstraints(true, constraints, entry.getKey(), ThreadContext.get(entry.getKey()), errors);
            }
        }
        if (errors.length() > 0) {
            throw new AuditException("Event " + eventName + " has incorrect data in the Thread Context: " + errors.toString());
        }
        msg.putAll(attributes);
        try {
            logEvent(msg);
        } catch (Throwable ex) {
            if (exceptionHandler == null) {
                defaultAuditExceptionHandler.handleException(msg, ex);
            } else {
                exceptionHandler.handleException(msg, ex);
            }
        }
    }

    private static void validateConstraints(boolean isRequestContext, Constraint[] constraints, String name,
                                            String value, StringBuilder errors) {
        for (Constraint constraint : constraints) {
            constraintPlugins.validateConstraint(isRequestContext, constraint.getConstraintType().getName(), name, value,
                    constraint.getValue(), errors);
        }
    }
}
