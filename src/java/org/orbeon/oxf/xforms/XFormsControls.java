/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.apache.log4j.Logger;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.oxf.xforms.control.*;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatControl;
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatIterationControl;
import org.orbeon.oxf.xforms.control.controls.XFormsTriggerControl;
import org.orbeon.oxf.xforms.control.controls.XXFormsDialogControl;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.event.events.*;
import org.orbeon.oxf.xforms.itemset.Itemset;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.saxon.dom4j.NodeWrapper;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.om.NodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents all this XForms containing document controls and the context in which they operate.
 */
public class XFormsControls implements XFormsObjectResolver {

    public static final String LOGGING_CATEGORY = "control";
    public static final Logger logger = LoggerFactory.createLogger(XFormsModel.class);
    public final IndentedLogger indentedLogger;

    private boolean initialized;
    private ControlTree initialControlTree;
    private ControlTree currentControlTree;

    private boolean dirtySinceLastRequest;

    private XFormsContainingDocument containingDocument;
    private XBLContainer rootContainer;
    
    private Map<String, Itemset> constantItems;

    // Options configured by properties
    private boolean isPlainValueChange;
    private boolean isInitialRefreshEvents;

    private static final boolean TESTING_DIALOG_OPTIMIZATION = false;

    public XFormsControls(XFormsContainingDocument containingDocument) {

        this.indentedLogger = containingDocument.getIndentedLogger(LOGGING_CATEGORY);

        this.containingDocument = containingDocument;
        this.rootContainer = this.containingDocument;

        // Create minimal tree
        initialControlTree = new ControlTree(XFormsProperties.isNoscript(containingDocument));
        currentControlTree = initialControlTree;

        // Get properties
        isPlainValueChange = XFormsProperties.isPlainValueChange(containingDocument);
        isInitialRefreshEvents = XFormsProperties.isInitialRefreshEvents(containingDocument);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public IndentedLogger getIndentedLogger() {
        return indentedLogger;
    }

    public boolean isDirtySinceLastRequest() {
        return dirtySinceLastRequest;
    }
    
    public void markDirtySinceLastRequest(boolean bindingsAffected) {
        dirtySinceLastRequest = true;
        if (bindingsAffected)
            currentControlTree.markBindingsDirty();
    }

    /**
     * Returns whether there is any event handler registered anywhere in the controls for the given event name.
     *
     * @param eventName event name, like xforms-value-changed
     * @return          true if there is a handler, false otherwise
     */
    public boolean hasHandlerForEvent(String eventName) {
        final Map eventNamesMap = containingDocument.getStaticState().getEventNamesMap();
        // Check for #all as well as specific event
        return eventNamesMap.get(XFormsConstants.XXFORMS_ALL_EVENTS) != null || eventNamesMap.get(eventName) != null;
    }

    /**
     * Initialize the controls if needed. This is called upon initial creation of the engine OR when new exernal events
     * arrive.
     *
     * TODO: this is called in XFormsContainingDocument.prepareForExternalEventsSequence() but it is not really an
     * initialization in that case.
     *
     * @param propertyContext   current context
     */
    public void initialize(PropertyContext propertyContext) {
        initializeState(propertyContext, false);
    }

    /**
     * Initialize the controls if needed, passing initial state information. This is called if the state of the engine
     * needs to be rebuilt.
     *
     * @param propertyContext       current context
     * @param evaluateItemsets      whether to evaluateItemsets (for dynamic state restoration)
     */
    public void initializeState(PropertyContext propertyContext, boolean evaluateItemsets) {

        final XFormsStaticState staticState = containingDocument.getStaticState();
        if (staticState != null && staticState.getControlsDocument() != null) {

            if (initialized) {
                // Use existing controls tree

                initialControlTree = currentControlTree;

                // Need to make sure that current == initial within controls
                visitAllControls(new XFormsControls.XFormsControlVisitorAdapter() {
                    public void startVisitControl(XFormsControl control) {
                        control.resetLocal();
                    }
                });

            } else {
                // Create new controls tree
                // NOTE: We set this first so that the tree is made available during construction to XPath functions like index() or xxforms:case() 
                currentControlTree = initialControlTree = new ControlTree(XFormsProperties.isNoscript(containingDocument));

                // Initialize new control tree
                currentControlTree.initialize(propertyContext, containingDocument, indentedLogger, rootContainer, evaluateItemsets);
            }

            // We are now clean
            dirtySinceLastRequest = false;
            initialControlTree.markBindingsClean();
        }

        rootContainer.getContextStack().resetBindingContext(propertyContext);// not sure we actually need to do this

        initialized = true;
    }

    /**
     * Serialize controls into the dynamic state. Only the information that cannot be rebuilt from the instances is
     * serialized.
     *
     * @param dynamicStateElement
     */
    public void serializeControls(Element dynamicStateElement) {
        final Element controlsElement = Dom4jUtils.createElement("controls");
        visitAllControls(new XFormsControls.XFormsControlVisitorAdapter() {
            public void startVisitControl(XFormsControl control) {
                final Map nameValues = control.serializeLocal();
                if (nameValues != null) {
                    final Element controlElement = controlsElement.addElement("control");
                    controlElement.addAttribute("effective-id", control.getEffectiveId());
                    for (Object o: nameValues.entrySet()) {
                        final Map.Entry currentEntry = (Map.Entry) o;
                        controlElement.addAttribute((String) currentEntry.getKey(), (String) currentEntry.getValue());
                    }
                }
            }
        });
        // Only add the element if necessary
        if (controlsElement.hasContent())
            dynamicStateElement.add(controlsElement);
    }

    /**
     * Get serialized control state as a Map. Only the information that cannot be rebuilt from the instances is
     * deserialized.
     *
     * @param   dynamicStateElement
     * @return  Map<String effectiveId, Element serializedState>
     */
    public Map<String, Element> getSerializedControlStateMap(Element dynamicStateElement) {
        final Map<String, Element> result = new HashMap<String, Element>();
        final Element controlsElement = dynamicStateElement.element("controls");
        if (controlsElement != null) {
            for (Element currentControlElement : Dom4jUtils.elements(controlsElement, "control")) {
                result.put(currentControlElement.attributeValue("effective-id"), currentControlElement);
            }
        }
        return result;
    }

    public XFormsContainingDocument getContainingDocument() {
        return containingDocument;
    }

    // TODO: Many callers of this won't get the proper context stack when dealing with components
    public XFormsContextStack getContextStack() {
        return rootContainer.getContextStack();
    }

    /**
     * Create a new repeat iteration for insertion into the current tree of controls.
     *
     * WARNING: The binding context must be set to the current iteration before calling.
     *
     * @param propertyContext   current context
     * @param repeatControl     repeat control
     * @param iterationIndex    new iteration to repeat (1..repeat size + 1)
     */
    public XFormsRepeatIterationControl createRepeatIterationTree(final PropertyContext propertyContext, XFormsContextStack.BindingContext bindingContext,
                                                                  XFormsRepeatControl repeatControl, int iterationIndex) {

        if (initialControlTree == currentControlTree && containingDocument.isHandleDifferences())
            throw new OXFException("Cannot call insertRepeatIteration() when initialControlTree == currentControlTree");

        final XFormsRepeatIterationControl repeatIterationControl;
        indentedLogger.startHandleOperation("controls", "adding iteration");
        repeatIterationControl = currentControlTree.createRepeatIterationTree(propertyContext, bindingContext, repeatControl, iterationIndex);
        indentedLogger.endHandleOperation();

        return repeatIterationControl;
    }

    /**
     * Evaluate all the controls if needed. Should be used before output initial XHTML and before computing differences
     * in XFormsServer.
     *
     * @param pipelineContext   current PipelineContext
     */
    public void evaluateControlValuesIfNeeded(PipelineContext pipelineContext) {

        indentedLogger.startHandleOperation("controls", "evaluating");
        {
            final Map effectiveIdsToControls = getCurrentControlTree().getEffectiveIdsToControls();
            // Evaluate all controls
            if (effectiveIdsToControls != null) {
                for (Object o: effectiveIdsToControls.entrySet()) {
                    final Map.Entry currentEntry = (Map.Entry) o;
                    final XFormsControl currentControl = (XFormsControl) currentEntry.getValue();
                    currentControl.evaluateIfNeeded(pipelineContext);
                }
            }
        }
        indentedLogger.endHandleOperation();
    }

    /**
     * Get the ControlTree computed in the initialize() method.
     */
    public ControlTree getInitialControlTree() {
        return initialControlTree;
    }

    /**
     * Get the last computed ControlTree.
     */
    public ControlTree getCurrentControlTree() {
        return currentControlTree;
    }

    /**
     * Clone the current controls tree if:
     *
     * 1. it hasn't yet been cloned
     * 2. we are not during the XForms engine initialization
     *
     * The rationale for #2 is that there is no controls comparison needed during initialization. Only during further
     * client requests do the controls need to be compared.
     */
    public void cloneInitialStateIfNeeded() {
        if (initialControlTree == currentControlTree && containingDocument.isHandleDifferences()) {
            indentedLogger.startHandleOperation("controls", "cloning");
            {
                try {
                    // NOTE: We clone "back", that is the new tree is used as the "initial" tree. This is done so that
                    // if we started working with controls in the initial tree, we can keep using those references safely.
                    initialControlTree = (ControlTree) currentControlTree.clone();
                } catch (CloneNotSupportedException e) {
                    throw new OXFException(e);
                }
            }
            indentedLogger.endHandleOperation();
        }
    }

    /**
     * Rebuild the controls tree bindings if needed.
     *
     * @param propertyContext   current context
     */
    public boolean updateControlBindingsIfNeeded(final PropertyContext propertyContext) {

        if (!initialized) {
            return false;
        } else {
            // This is the regular case

            // Don't do anything if bindings are clean
            if (!currentControlTree.isBindingsDirty())
                return false;

            // Clone if needed
            cloneInitialStateIfNeeded();

            indentedLogger.startHandleOperation("controls", "updating bindings");
            final UpdateBindingsListener listener = new UpdateBindingsListener(propertyContext, currentControlTree.getEffectiveIdsToControls(), currentControlTree.getEventsToDispatch());
            {
                // Visit all controls and update their bindings
                visitControlElementsHandleRepeat(propertyContext, containingDocument, rootContainer, listener);
            }
            indentedLogger.endHandleOperation(
                    "controls updated", Integer.toString(listener.getUpdateCount()),
                    "repeat iterations", Integer.toString(listener.getIterationCount())
            );

//            final int ITERATIONS = 1;
//            for (int k = 0; k < ITERATIONS; k++) {
//
//                final UpdateBindingsListener listener = new UpdateBindingsListener(pipelineContext, currentControlTree.getEffectiveIdsToControls(), currentControlTree.getEventsToDispatch());
//                {
//                    // Visit all controls and update their bindings
//                    visitControlElementsHandleRepeat(pipelineContext, containingDocument, rootContainer, listener);
//                }
//                if (k == ITERATIONS - 1) {
//                    containingDocument.endHandleOperation(new String[] {
//                            "controls updated", Integer.toString(listener.getUpdateCount()),
//                            "repeat iterations", Integer.toString(listener.getIterationCount())
//                    });
//                } else {
//                }
//            }

            // Controls are clean
            initialControlTree.markBindingsClean();
            currentControlTree.markBindingsClean();

            return true;
        }
    }

    /**
     * Get object with the effective id specified.
     *
     * @param effectiveId   effective id of the target
     * @return              object, or null if not found
     */
    public Object getObjectByEffectiveId(String effectiveId) {
        // Until xforms-ready is dispatched, ids map may be null
        final Map effectiveIdsToControls = currentControlTree.getEffectiveIdsToControls();
        return (effectiveIdsToControls != null) ? effectiveIdsToControls.get(effectiveId) : null;
    }

    /**
     * Resolve an object. This optionally depends on a source control, and involves resolving whether the source is within a
     * repeat or a component.
     *
     * @param sourceControlEffectiveId  effective id of the source control, or null
     * @param targetId                  id of the target
     * @return                          object, or null if not found
     */
    public Object resolveObjectById(String sourceControlEffectiveId, String targetId) {
        final String effectiveControlId = getCurrentControlTree().findEffectiveControlId(sourceControlEffectiveId, targetId);
        return (effectiveControlId != null) ? getObjectByEffectiveId(effectiveControlId) : null;
    }

    /**
     * Visit all the controls elements by following repeats to allow creating the actual control.
     */
    public static void visitControlElementsHandleRepeat(PropertyContext propertyContext, XFormsContainingDocument containingDocument, XBLContainer rootContainer, ControlElementVisitorListener controlElementVisitorListener) {
        rootContainer.getContextStack().resetBindingContext(propertyContext);
        final boolean isOptimizeRelevance = XFormsProperties.isOptimizeRelevance(containingDocument);
        final Element rootElement = containingDocument.getStaticState().getControlsDocument().getRootElement();

        visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                containingDocument.getStaticState(), rootContainer, rootElement, "", "");
    }

    public static void visitControlElementsHandleRepeat(PropertyContext propertyContext, XFormsRepeatControl enclosingRepeatControl, int iterationIndex, ControlElementVisitorListener controlElementVisitorListener) {

        final XFormsContainingDocument containingDocument = enclosingRepeatControl.getXBLContainer().getContainingDocument();
        final boolean isOptimizeRelevance = XFormsProperties.isOptimizeRelevance(containingDocument);

        // Set binding context on the particular repeat iteration
        final XFormsContextStack contextStack = enclosingRepeatControl.getXBLContainer().getContextStack();
        contextStack.setBinding(enclosingRepeatControl);
        contextStack.pushIteration(iterationIndex);

        // Start visiting children of the xforms:repeat element
        final Element repeatControlElement = containingDocument.getStaticState().getControlInfoMap().get(enclosingRepeatControl.getPrefixedId()).getElement();
        XFormsControls.visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                containingDocument.getStaticState(), enclosingRepeatControl.getXBLContainer(), repeatControlElement,
                XFormsUtils.getEffectiveIdPrefix(enclosingRepeatControl.getEffectiveId()),
                XFormsUtils.getEffectiveIdSuffix(XFormsUtils.getIterationEffectiveId(enclosingRepeatControl.getEffectiveId(), iterationIndex)));
    }

    public static void visitControlElementsHandleRepeat(PropertyContext propertyContext, XFormsContainerControl containerControl, ControlElementVisitorListener controlElementVisitorListener) {

        final XFormsControl control = (XFormsControl) containerControl;
        final XFormsContainingDocument containingDocument = control.getXBLContainer().getContainingDocument();
        final boolean isOptimizeRelevance = XFormsProperties.isOptimizeRelevance(containingDocument);

        // Set binding context on the particular control
        final XFormsContextStack contextStack = control.getXBLContainer().getContextStack();
        contextStack.setBinding(control);

        // Start visiting children of the xforms:repeat element
        XFormsControls.visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                containingDocument.getStaticState(), control.getXBLContainer(), control.getControlElement(),
                XFormsUtils.getEffectiveIdPrefix(control.getEffectiveId()),
                XFormsUtils.getEffectiveIdSuffix(control.getEffectiveId()));
    }

    private static void visitControlElementsHandleRepeat(PropertyContext propertyContext, ControlElementVisitorListener controlElementVisitorListener,
                                                    boolean isOptimizeRelevance, XFormsStaticState staticState, XBLContainer currentContainer,
                                                    Element containerElement, String idPrefix, String idPostfix) {

        int variablesCount = 0;
        final XFormsContextStack currentContextStack = currentContainer.getContextStack();
        for (Object o: containerElement.elements()) {
            final Element currentControlElement = (Element) o;
            final String currentControlURI = currentControlElement.getNamespaceURI();
            final String currentControlName = currentControlElement.getName();

            final String staticControlId = currentControlElement.attributeValue("id");
            final String effectiveControlId
                    = idPrefix + staticControlId + (idPostfix.equals("") ? "" : XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_1 + idPostfix);

            if (currentControlName.equals("repeat")) {
                // Handle xforms:repeat
                currentContextStack.pushBinding(propertyContext, currentControlElement);

                // Visit xforms:repeat element
                controlElementVisitorListener.startVisitControl(currentContainer, currentControlElement, effectiveControlId);

                // Iterate over current xforms:repeat nodeset
                final List currentNodeSet = currentContextStack.getCurrentNodeset();
                if (currentNodeSet != null) {
                    for (int iterationIndex = 1; iterationIndex <= currentNodeSet.size(); iterationIndex++) {
                        // Push "artificial" binding with just current node in nodeset
                        currentContextStack.pushIteration(iterationIndex);
                        {
                            // Handle children of xforms:repeat
                            // TODO: handle isOptimizeRelevance()

                            // Compute repeat iteration id
                            final String iterationEffectiveId = XFormsUtils.getIterationEffectiveId(effectiveControlId, iterationIndex);

                            final boolean recurse = controlElementVisitorListener.startRepeatIteration(currentContainer, iterationIndex, iterationEffectiveId);
                            if (recurse) {
                                // When updating controls, the callee has the option of disabling recursion into an
                                // iteration. This is used for handling new iterations.
                                final String newIdPostfix = idPostfix.equals("") ? Integer.toString(iterationIndex) : (idPostfix + XFormsConstants.REPEAT_HIERARCHY_SEPARATOR_2 + iterationIndex);
                                visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                                        staticState, currentContainer, currentControlElement, idPrefix, newIdPostfix);
                            }
                            controlElementVisitorListener.endRepeatIteration(iterationIndex);
                        }
                        currentContextStack.popBinding();
                    }
                }

                controlElementVisitorListener.endVisitControl(currentControlElement, effectiveControlId);
                currentContextStack.popBinding();
            } else if (XFormsControlFactory.isContainerControl(currentControlURI, currentControlName)) {
                // Handle XForms grouping controls
                currentContextStack.pushBinding(propertyContext, currentControlElement);
                final XFormsControl newControl = controlElementVisitorListener.startVisitControl(currentContainer, currentControlElement, effectiveControlId);
                final XFormsContextStack.BindingContext currentBindingContext = currentContextStack.getCurrentBindingContext();
                {
                    // Recurse into grouping control and components if we don't optimize relevance, OR if we do
                    // optimize and we are not bound to a node OR we are bound to a relevant node

                    // NOTE: Simply excluding non-selected cases with the expression below doesn't work. So for
                    // now, we don't consider hidden cases as non-relevant. In the future, we might want to improve
                    // this.
                    // && (!controlName.equals("case") || isCaseSelectedByControlElement(controlElement, effectiveControlId, idPostfix))

                    if (TESTING_DIALOG_OPTIMIZATION && newControl instanceof XXFormsDialogControl) {// TODO: FOR TESTING DIALOG OPTIMIZATION
                        // Visit dialog children only if dialog is visible
                        if (((XXFormsDialogControl) newControl).isVisible()) {
                            visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                                    staticState, currentContainer, currentControlElement, idPrefix, idPostfix);
                        }
                    } else {
                        if (!isOptimizeRelevance
                                || (!currentBindingContext.isNewBind()
                                || (currentBindingContext.getSingleNode() != null && InstanceData.getInheritedRelevant(currentBindingContext.getSingleNode())))) {

                            visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                                    staticState, currentContainer, currentControlElement, idPrefix, idPostfix);
                        }
                    }
                }
                controlElementVisitorListener.endVisitControl(currentControlElement, effectiveControlId);
                currentContextStack.popBinding();
            } else if (XFormsControlFactory.isCoreControl(currentControlURI, currentControlName)) {
                // Handle leaf control
                currentContextStack.pushBinding(propertyContext, currentControlElement);
                controlElementVisitorListener.startVisitControl(currentContainer, currentControlElement, effectiveControlId);
                controlElementVisitorListener.endVisitControl(currentControlElement, effectiveControlId);
                currentContextStack.popBinding();
            } else if (currentControlName.equals("variable")) {
                // Handle xxforms:variable specifically

                // Create variable object
                final Variable variable = new Variable(currentContainer, currentContextStack, currentControlElement);

                // Push the variable on the context stack. Note that we do as if each variable was a "parent" of the following controls and variables.
                // NOTE: The value is computed immediately. We should use Expression objects and do lazy evaluation in the future.
                currentContextStack.pushVariable(currentControlElement, variable.getVariableName(), variable.getVariableValue(propertyContext, true));

                variablesCount++;
            } else if (staticState.getXblBindings().isComponent(currentControlElement.getQName())) {
                // Handle components

                // NOTE: don't push the binding here, this is handled if necessary by the component implementation
                final XFormsComponentControl newControl = (XFormsComponentControl) controlElementVisitorListener.startVisitControl(currentContainer, currentControlElement, effectiveControlId);

                // Recurse into the shadow component tree
                final Element shadowTreeDocumentElement = staticState.getXblBindings().getCompactShadowTree(idPrefix + staticControlId);
                visitControlElementsHandleRepeat(propertyContext, controlElementVisitorListener, isOptimizeRelevance,
                        staticState, newControl.getNestedContainer(), shadowTreeDocumentElement, newControl.getNestedContainer().getFullPrefix(), idPostfix);

                controlElementVisitorListener.endVisitControl(currentControlElement, effectiveControlId);

            } else {
                // Ignore, this is not a control
            }
        }

        // Unscope all variables
        for (int i = 0; i < variablesCount; i++)
            currentContextStack.popBinding();
    }

    /**
     * Visit all the current XFormsControls.
     */
    public void visitAllControls(XFormsControlVisitorListener xformsControlVisitorListener) {
        currentControlTree.visitAllControls(xformsControlVisitorListener);
    }

    public static interface ControlElementVisitorListener {
        public XFormsControl startVisitControl(XBLContainer container, Element controlElement, String effectiveControlId);
        public void endVisitControl(Element controlElement, String effectiveControlId);
        public boolean startRepeatIteration(XBLContainer container, int iteration, String effectiveIterationId);
        public void endRepeatIteration(int iteration);
    }

    public static interface XFormsControlVisitorListener {
        public void startVisitControl(XFormsControl control);
        public void endVisitControl(XFormsControl control);
    }

    public static class XFormsControlVisitorAdapter implements XFormsControlVisitorListener {
        public void startVisitControl(XFormsControl control) {}
        public void endVisitControl(XFormsControl control) {}
    }

    /**
     * Get the items for a given control id. This is not an effective id, but an original control id.
     *
     * @param controlId     original control id
     * @return              List of Item
     */
    public Itemset getConstantItems(String controlId) {
        if (constantItems == null)
            return null;
        else
            return constantItems.get(controlId);
    }

    /**
     * Set the items for a given control id. This is not an effective id, but an original control id.
     *
     * @param controlId     static control id
     * @param items         List<Item>
     */
    public void setConstantItems(String controlId, Itemset items) {
        if (constantItems == null)
            constantItems = new HashMap<String, Itemset>();
        constantItems.put(controlId, items);
    }

    private static class UpdateBindingsListener implements ControlElementVisitorListener {

        private final PropertyContext propertyContext;
        private final Map effectiveIdsToControls;
        private final Map<String, EventSchedule> eventsToDispatch;

        private transient int updateCount;
        private transient int iterationCount;

        private UpdateBindingsListener(PropertyContext propertyContext, Map effectiveIdsToControls, Map<String, EventSchedule> eventsToDispatch) {
            this.propertyContext = propertyContext;
            this.effectiveIdsToControls = effectiveIdsToControls;
            this.eventsToDispatch = eventsToDispatch;
        }

        private Map<String, XFormsRepeatIterationControl> newIterationsMap = new HashMap<String, XFormsRepeatIterationControl>();

        public XFormsControl startVisitControl(XBLContainer container, Element controlElement, String effectiveControlId) {

            updateCount++;

            final XFormsControl control = (XFormsControl) effectiveIdsToControls.get(effectiveControlId);

            final XFormsContextStack.BindingContext oldBindingContext = control.getBindingContext();
            final XFormsContextStack.BindingContext newBindingContext = container.getContextStack().getCurrentBindingContext();

            // Handle special relevance events
            // NOTE: We don't dispatch events to repeat iterations
            if (control instanceof XFormsSingleNodeControl && !(control instanceof XFormsRepeatIterationControl)) {
                final XFormsSingleNodeControl singleNodeControl = (XFormsSingleNodeControl) control;
                final NodeInfo boundNode1 = singleNodeControl.getBoundNode();
                final NodeInfo boundNode2 = newBindingContext.getSingleNode();

                boolean found = false;
                int eventType = 0;
                if (boundNode1 != null && InstanceData.getInheritedRelevant(boundNode1) && boundNode2 == null) {
                    // A control was bound to a node and relevant, but has become no longer bound to a node
                    found = true;
                    eventType = EventSchedule.RELEVANT_BINDING;
                } else if (boundNode1 == null && boundNode2 != null && InstanceData.getInheritedRelevant(boundNode2)) {
                    // A control was not bound to a node, but has now become bound and relevant
                    found = true;
                    eventType = EventSchedule.RELEVANT_BINDING;
                } else if (boundNode1 != null && boundNode2 != null && !boundNode1.isSameNodeInfo(boundNode2)) {
                    // The control is now bound to a different node
                    // In this case, we schedule the control to dispatch all the events

                    // NOTE: This is not really proper according to the spec, but it does help applications to
                    // force dispatching in such cases
                    found = true;
                    eventType = EventSchedule.ALL;
                }

                // Remember that we need to dispatch information about this control
                if (found) {
                    eventsToDispatch.put(singleNodeControl.getEffectiveId(),
                            new EventSchedule(singleNodeControl.getEffectiveId(), eventType, singleNodeControl));
                }
            }

            if (control instanceof XFormsRepeatControl) {
                // Handle repeat
                // TODO: handle this through inheritance

                // Get old nodeset
                final List<Item> oldRepeatNodeset = oldBindingContext.getNodeset();

                // Get new nodeset
                final List<Item> newRepeatNodeset = newBindingContext.getNodeset();

                // Set new current binding for control element
                control.setBindingContext(propertyContext, newBindingContext);

                // Update iterations
                final List<XFormsRepeatIterationControl> newIterations = ((XFormsRepeatControl) control).updateIterations(propertyContext, oldRepeatNodeset, newRepeatNodeset, null);

                // Remember newly created iterations so we don't recurse into them in startRepeatIteration()
                for (Object newIteration: newIterations) {
                    final XFormsRepeatIterationControl repeatIterationControl = (XFormsRepeatIterationControl) newIteration;
                    newIterationsMap.put(repeatIterationControl.getEffectiveId(), repeatIterationControl);
                }
            } else if (TESTING_DIALOG_OPTIMIZATION && control instanceof XXFormsDialogControl) {// TODO: TESTING DIALOG OPTIMIZATION
                // Handle dialog
                // TODO: handle this through inheritance

                control.setBindingContext(propertyContext, newBindingContext);

                final XXFormsDialogControl dialogControl = (XXFormsDialogControl) control;
                final boolean isVisible = dialogControl.isVisible();
                dialogControl.updateContent(propertyContext, isVisible);

            } else {
                // Handle all other controls
                // TODO: handle other container controls

                // Set new current binding for control element
                control.setBindingContext(propertyContext, newBindingContext);
            }

            // Mark the control as dirty so it gets reevaluated
            // NOTE: existing repeat iterations are marked dirty below in startRepeatIteration()
            control.markDirty();

            return control;
        }

        public void endVisitControl(Element controlElement, String effectiveControlId) {
        }

        public boolean startRepeatIteration(XBLContainer container, int iteration, String effectiveIterationId) {

            iterationCount++;

            // Get reference to iteration control
            final XFormsRepeatIterationControl repeatIterationControl = (XFormsRepeatIterationControl) effectiveIdsToControls.get(effectiveIterationId);

            // Check whether this is an existing iteration as opposed to a newly created iteration
            final boolean isExistingIteration = newIterationsMap.get(effectiveIterationId) == null;
            if (isExistingIteration) {
                // Mark the control as dirty so it gets reevaluated
                repeatIterationControl.markDirty();
                // NOTE: We don't need to call repeatIterationControl.setBindingContext() because XFormsRepeatControl/updateIterations() does it already
            }

            // Allow recursing into this iteration only if it is not a newly created iteration
            return isExistingIteration;
        }

        public void endRepeatIteration(int iteration) {
        }

        public int getUpdateCount() {
            return updateCount;
        }

        public int getIterationCount() {
            return iterationCount;
        }
    }
    
    public void doRefresh(final PropertyContext propertyContext, XFormsModel model) {
        // "1. All UI bindings should be reevaluated as necessary."

        // "2. A node can be changed by confirmed user input to a form control, by
        // xforms-recalculate (section 4.3.6) or by the setvalue (section 10.1.9) action. If the
        // value of an instance data node was changed, then the node must be marked for
        // dispatching the xforms-value-changed event."

        // "3. If the xforms-value-changed event is marked for dispatching, then all of the
        // appropriate model item property notification events must also be marked for
        // dispatching (xforms-optional or xforms-required, xforms-readwrite or xforms-readonly,
        // and xforms-enabled or xforms-disabled)."

        // "4. For each form control, each notification event that is marked for dispatching on
        // the bound node must be dispatched (xforms-value-changed, xforms-valid,
        // xforms-invalid, xforms-optional, xforms-required, xforms-readwrite, xforms-readonly,
        // and xforms-enabled, xforms-disabled). The notification events xforms-out-of-range or
        // xforms-in-range must also be dispatched as appropriate. This specification does not
        // specify an ordering for the events."



        // Don't do anything if there are no children controls
        if (getCurrentControlTree().getChildren() == null) {
            indentedLogger.logDebug("model", "not performing refresh because no controls are available");
            // Don't forget to clear the flag or we risk infinite recursion
            model.refreshDone();
            return;
        }

        indentedLogger.startHandleOperation("model", "performing refresh", "model id", model.getEffectiveId());

        // Update control bindings if needed
        updateControlBindingsIfNeeded(propertyContext);

        // Obtain global information about event handlers. This is a rough optimization so we can avoid sending certain
        // types of events below.
        final boolean isAllowSendingValueChangedEvents = hasHandlerForEvent(XFormsEvents.XFORMS_VALUE_CHANGED);
        final boolean isAllowSendingRequiredEvents = hasHandlerForEvent(XFormsEvents.XFORMS_REQUIRED) || hasHandlerForEvent(XFormsEvents.XFORMS_OPTIONAL);
        final boolean isAllowSendingRelevantEvents = hasHandlerForEvent(XFormsEvents.XFORMS_ENABLED) || hasHandlerForEvent(XFormsEvents.XFORMS_DISABLED);
        final boolean isAllowSendingReadonlyEvents = hasHandlerForEvent(XFormsEvents.XFORMS_READONLY) || hasHandlerForEvent(XFormsEvents.XFORMS_READWRITE);
        final boolean isAllowSendingValidEvents = hasHandlerForEvent(XFormsEvents.XFORMS_VALID) || hasHandlerForEvent(XFormsEvents.XFORMS_INVALID);

        final boolean isAllowSendingUIEvents = isAllowSendingValueChangedEvents || isAllowSendingRequiredEvents || isAllowSendingRelevantEvents || isAllowSendingReadonlyEvents || isAllowSendingValidEvents;
        if (isAllowSendingUIEvents) {
            // There are potentially event handlers for UI events, so do the whole processing

            // If this is the first refresh we mark nodes to dispatch MIP events
            final boolean isFirstRefresh = isInitialRefreshEvents && containingDocument.isInitializationFirstRefreshClear();

            // Build list of events to send
            final Map<String, EventSchedule> relevantBindingEvents = getCurrentControlTree().getEventsToDispatch();
            final List<EventSchedule> eventsToDispatch = new ArrayList<EventSchedule>();

            // Iterate through controls and check the nodes they are bound to
            visitAllControls(new XFormsControlVisitorAdapter() {
                public void startVisitControl(XFormsControl control) {

                    // We must be an XFormsSingleNodeControl
                    // NOTE: We don't dispatch events to repeat iterations
                    if (!(control instanceof XFormsSingleNodeControl && !(control instanceof XFormsRepeatIterationControl)))
                        return;

                    // This can happen if control is not bound to anything (includes xforms:group[not(@ref) and not(@bind)])
                    final NodeInfo currentNodeInfo = ((XFormsSingleNodeControl) control).getBoundNode();
                    if (currentNodeInfo == null)
                        return;

                    // We only dispatch events for controls bound to a mutable document
                    // TODO: what about initial events? those could be sent.
                    if (!(currentNodeInfo instanceof NodeWrapper))
                        return;

                    // Check if value has changed

                    // NOTE: For the purpose of dispatching value change and MIP events, we used to make a
                    // distinction between value controls and plain single-node controls. However it seems that it is
                    // still reasonable to dispatch those events to xforms:group, xforms:switch, and even repeat
                    // iterations if they are bound.

                    final String effectiveId = control.getEffectiveId();
                    final EventSchedule existingEventSchedule = (relevantBindingEvents == null) ? null : relevantBindingEvents.get(effectiveId);

                    // Allow dispatching value change to:
                    // o relevant control
                    // o whose value changed
                    //
                    // NOTE: We tried dispatching also during first refresh, but only if it is not a container control
                    // OR if the bound node is simple content. However, right now we have decided against dispatching
                    // this during initialization. See:
                    //
                    //   http://wiki.orbeon.com/forms/doc/contributor-guide/xforms-ui-events

//                     This last part of the logic is there to prevent dispatching spurious value change events for all
//                     groups during initialization, while still allowing listening to value changes on groups that have
//                     simple content and therefore can hold a value.
//
//                     Whether the control is a container control: group, switch, repeat iteration
//                    final boolean isContainerControl = control instanceof XFormsSingleNodeContainerControl;
//                    final boolean isShouldSendValueChangeEvent
//                            = newRelevantState
//                                && (isControlValueChanged
//                                    || isFirstRefresh && (!isContainerControl || Dom4jUtils.isSimpleContent((Node) ((NodeWrapper) currentNodeInfo).getUnderlyingNode())));

                    // NOTE: In the whole refresh process, no control evaluation takes place. This is needed at this
                    // point because with multiple models, a refresh might occur before all models have been RRR. This
                    // means that evaluating here might incorrect values for MIPs and control values.

                    // Don't dispatch events to static readonly triggers, as they in fact behave as if they were not relevant!
                    if (control instanceof XFormsTriggerControl && XFormsSingleNodeControl.isStaticReadonlyNoEvaluate((XFormsSingleNodeControl) control)) {
                        return;
                    }

                    final boolean newRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);
                    final boolean isControlValueChanged = InstanceData.isValueChanged(currentNodeInfo);
                    // TODO: if control *becomes* non-relevant and value changed, arguably we should dispatch the value-changed event
                    final boolean isShouldSendValueChangeEvent = newRelevantState && isControlValueChanged;

                    if (isFirstRefresh) {
                        // Special processing for first refresh

                        // Don't dispatch any value change
                        // NOP

                        // Display events only if the control is relevant
                        if (newRelevantState) {

                            // Dispatch xforms-enabled if needed
                            if (isAllowSendingRelevantEvents) {
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                            }

                            // Dispatch events only if the MIP value is different from the default

                            // Dispatch xforms-required if needed
                            if (isAllowSendingRequiredEvents && InstanceData.getRequired(currentNodeInfo)) {
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                            }

                            // Dispatch xforms-readonly if needed
                            if (isAllowSendingReadonlyEvents && InstanceData.getInheritedReadonly(currentNodeInfo)) {
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                            }

                            // Dispatch xforms-invalid if needed
                            if (isAllowSendingValidEvents && !InstanceData.getValid(currentNodeInfo)) {
                                addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                            }
                        }

                    } else {
                        // Subsequent refreshes

                        if (isShouldSendValueChangeEvent) {
                            if (isAllowSendingValueChangedEvents) {
                                // Dispatch value change and...

                                if (!isPlainValueChange) {
                                    // ... all MIP events
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.ALL);
                                } else {
                                    // ... nothing else
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALUE);
                                }
                            } else {
                                if (!isPlainValueChange) {
                                    // Dispatch all the allowed MIP events

                                    if (isAllowSendingRequiredEvents)
                                        addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                                    if (isAllowSendingRelevantEvents)
                                        addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                                    if (isAllowSendingReadonlyEvents)
                                        addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                                    if (isAllowSendingValidEvents)
                                        addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                                }
                            }
                        }

                        if (!isShouldSendValueChangeEvent || isPlainValueChange) {
                            // Send individual MIP events
                            // Come here if MIP events are not already handled above

                            // Dispatch xforms-optional/xforms-required if needed
                            if (isAllowSendingRequiredEvents) {
                                // Send only when value has changed
                                final boolean previousRequiredState = InstanceData.getPreviousRequiredState(currentNodeInfo);
                                final boolean newRequiredState = InstanceData.getRequired(currentNodeInfo);

                                if (previousRequiredState != newRequiredState) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.REQUIRED);
                                }
                            }
                            // Dispatch xforms-enabled/xforms-disabled if needed
                            if (isAllowSendingRelevantEvents) {
                                // Send only when value has changed
                                final boolean previousRelevantState = InstanceData.getPreviousInheritedRelevantState(currentNodeInfo);

                                if (previousRelevantState != newRelevantState) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.RELEVANT);
                                }
                            }
                            // Dispatch xforms-readonly/xforms-readwrite if needed
                            if (isAllowSendingReadonlyEvents) {
                                final boolean previousReadonlyState = InstanceData.getPreviousInheritedReadonlyState(currentNodeInfo);
                                final boolean newReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);

                                if (previousReadonlyState != newReadonlyState) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.READONLY);
                                }
                            }

                            // Dispatch xforms-valid/xforms-invalid if needed

                            // NOTE: There is no mention in the spec that these events should be displatched automatically
                            // when the value has changed, contrary to the other events above.
                            if (isAllowSendingValidEvents) {
                                final boolean previousValidState = InstanceData.getPreviousValidState(currentNodeInfo);
                                final boolean newValidState = InstanceData.getValid(currentNodeInfo);

                                if (previousValidState != newValidState) {
                                    addEventToSchedule(existingEventSchedule, effectiveId, EventSchedule.VALID);
                                }
                            }
                        }
                    }
                }

                private void addEventToSchedule(EventSchedule eventSchedule, String effectiveControlId, int type) {
                    if (eventSchedule == null)
                        eventsToDispatch.add(new EventSchedule(effectiveControlId, type));
                    else
                        eventSchedule.updateType(type);
                }
            });

            // Clear InstanceData event state
            // NOTE: We clear for all models, as we are processing refresh events for all models here. This may have to be changed in the future.
            containingDocument.synchronizeInstanceDataEventState();
            getCurrentControlTree().clearEventsToDispatch();

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            model.refreshDone();

            // Add "relevant binding" events
            if (relevantBindingEvents != null)
                eventsToDispatch.addAll(relevantBindingEvents.values());

            // Send events and (try to) make sure the event corresponds to the current instance data
            // NOTE: event order and the exact steps to take are under-specified in 1.0.
            for (EventSchedule eventSchedule : eventsToDispatch) {

                final String controlInfoId = eventSchedule.getEffectiveControlId();
                final int type = eventSchedule.getType();
                final boolean isRelevantBindingEvent = (type & EventSchedule.RELEVANT_BINDING) != 0;

                final XFormsControl xformsControl = (XFormsControl) getObjectByEffectiveId(controlInfoId);

                if (!isRelevantBindingEvent) {
                    // Regular type of event

                    if (xformsControl == null) {
                        // In this case, the algorithm in the spec is not clear. Many thing can have happened between the
                        // initial determination of a control bound to a changing node, and now, including many events and
                        // actions.
                        continue;
                    }

                    // Re-obtain node to which control is bound, in case things have changed (includes xforms:group[not(@ref) and not(@bind)])
                    final NodeInfo currentNodeInfo = ((XFormsSingleNodeControl) xformsControl).getBoundNode();
                    if (currentNodeInfo == null) {
                        // See comment above about things that can have happened since.
                        continue;
                    }

                    // We only dispatch events for controls bound to a mutable document
                    if (!(currentNodeInfo instanceof NodeWrapper))
                        continue;

                    // "The XForms processor is not considered to be executing an outermost action handler at the time that it
                    // performs deferred update behavior for XForms models. Therefore, event handlers for events dispatched to
                    // the user interface during the deferred refresh behavior are considered to be new outermost action
                    // handler."

                    final XBLContainer container = xformsControl.getXBLContainer();

                    if (isAllowSendingValueChangedEvents && (type & EventSchedule.VALUE) != 0) {
                        container.dispatchEvent(propertyContext, new XFormsValueChangeEvent(containingDocument, xformsControl));
                    }
                    if (currentNodeInfo != null && currentNodeInfo instanceof NodeWrapper) {
                        if (isAllowSendingRequiredEvents && (type & EventSchedule.REQUIRED) != 0) {
                            final boolean currentRequiredState = InstanceData.getRequired(currentNodeInfo);
                            if (currentRequiredState) {
                                container.dispatchEvent(propertyContext, new XFormsRequiredEvent(containingDocument, xformsControl));
                            } else {
                                container.dispatchEvent(propertyContext, new XFormsOptionalEvent(containingDocument, xformsControl));
                            }
                        }
                        if (isAllowSendingRelevantEvents && (type & EventSchedule.RELEVANT) != 0) {
                            final boolean currentRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);
                            if (currentRelevantState) {
                                container.dispatchEvent(propertyContext, new XFormsEnabledEvent(containingDocument, xformsControl));
                            } else {
                                container.dispatchEvent(propertyContext, new XFormsDisabledEvent(containingDocument, xformsControl));
                            }
                        }
                        if (isAllowSendingReadonlyEvents && (type & EventSchedule.READONLY) != 0) {
                            final boolean currentReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);
                            if (currentReadonlyState) {
                                container.dispatchEvent(propertyContext, new XFormsReadonlyEvent(containingDocument, xformsControl));
                            } else {
                                container.dispatchEvent(propertyContext, new XFormsReadwriteEvent(containingDocument, xformsControl));
                            }
                        }
                        if (isAllowSendingValidEvents && (type & EventSchedule.VALID) != 0) {
                            final boolean currentValidState = InstanceData.getValid(currentNodeInfo);
                            if (currentValidState) {
                                container.dispatchEvent(propertyContext, new XFormsValidEvent(containingDocument, xformsControl));
                            } else {
                                container.dispatchEvent(propertyContext, new XFormsInvalidEvent(containingDocument, xformsControl));
                            }
                        }
                    }
                } else {
                    // Handle special case of "relevant binding" events, i.e. relevance that changes because a node becomes
                    // bound or unbound to a node.

                    if (xformsControl != null) {

                        // If control is not bound (e.g. xforms:group[not(@ref) and not(@bind)]) no events are sent
                        final boolean isControlBound = xformsControl.getBindingContext().isNewBind();
                        if (!isControlBound)
                            continue;

                        // Re-obtain node to which control is bound, in case things have changed
                        final NodeInfo currentNodeInfo = ((XFormsSingleNodeControl) xformsControl).getBoundNode();
                        if (currentNodeInfo != null) {

                            // We only dispatch value-changed and other events for controls bound to a mutable document
                            if (!(currentNodeInfo instanceof NodeWrapper))
                                continue;

                            final boolean currentRelevantState = InstanceData.getInheritedRelevant(currentNodeInfo);
                            if (currentRelevantState) {
                                // The control is newly bound to a relevant node

                                final XBLContainer container = xformsControl.getXBLContainer();

                                if (isAllowSendingRelevantEvents) {
                                    container.dispatchEvent(propertyContext, new XFormsEnabledEvent(containingDocument, xformsControl));
                                }

                                // Also send other MIP events
                                if (isAllowSendingRequiredEvents) {
                                    final boolean currentRequiredState = InstanceData.getRequired(currentNodeInfo);
                                    if (currentRequiredState) {
                                        container.dispatchEvent(propertyContext, new XFormsRequiredEvent(containingDocument, xformsControl));
                                    } else {
                                        container.dispatchEvent(propertyContext, new XFormsOptionalEvent(containingDocument, xformsControl));
                                    }
                                }

                                if (isAllowSendingReadonlyEvents) {
                                    final boolean currentReadonlyState = InstanceData.getInheritedReadonly(currentNodeInfo);
                                    if (currentReadonlyState) {
                                        container.dispatchEvent(propertyContext, new XFormsReadonlyEvent(containingDocument, xformsControl));
                                    } else {
                                        container.dispatchEvent(propertyContext, new XFormsReadwriteEvent(containingDocument, xformsControl));
                                    }
                                }

                                if (isAllowSendingValidEvents) {
                                    final boolean currentValidState = InstanceData.getValid(currentNodeInfo);
                                    if (currentValidState) {
                                        container.dispatchEvent(propertyContext, new XFormsValidEvent(containingDocument, xformsControl));
                                    } else {
                                        container.dispatchEvent(propertyContext, new XFormsInvalidEvent(containingDocument, xformsControl));
                                    }
                                }
                            }
                        } else {
                            // The control is not bound to a node
                            sendDefaultEventsForDisabledControl(propertyContext, xformsControl,
                                    isAllowSendingRequiredEvents, isAllowSendingRelevantEvents, isAllowSendingReadonlyEvents, isAllowSendingValidEvents);
                        }
                    } else {
                        // The control no longer exists
                        if (eventSchedule.getXFormsControl() != null) {
                            // In this case, we get a reference to the "old" control
                            sendDefaultEventsForDisabledControl(propertyContext, eventSchedule.getXFormsControl(),
                                    isAllowSendingRequiredEvents, isAllowSendingRelevantEvents, isAllowSendingReadonlyEvents, isAllowSendingValidEvents);
                        }
                    }
                }
            }
        } else {
            // No UI events to send because there is no event handlers for any of them
            indentedLogger.logDebug("model", "refresh skipping sending of UI events because no listener was found", "model id", model.getEffectiveId());

            // NOTE: We clear for all models, as we are processing refresh events for all models here. This may have to be changed in the future.
            containingDocument.synchronizeInstanceDataEventState();
            getCurrentControlTree().clearEventsToDispatch();

            // "Actions that directly invoke rebuild, recalculate, revalidate, or refresh always
            // have an immediate effect, and clear the corresponding flag."
            model.refreshDone();
        }

        indentedLogger.endHandleOperation();
    }

    private void sendDefaultEventsForDisabledControl(PropertyContext propertyContext, XFormsControl xformsControl,
                                                     boolean isAllowSendingRequiredEvents, boolean isAllowSendingRelevantEvents,
                                                     boolean isAllowSendingReadonlyEvents, boolean isAllowSendingValidEvents) {

        final XBLContainer container = xformsControl.getXBLContainer();

        // Control is disabled
        if (isAllowSendingRelevantEvents)
            container.dispatchEvent(propertyContext, new XFormsDisabledEvent(containingDocument, xformsControl));

        // Send events for default MIP values
        if (isAllowSendingRequiredEvents)
            container.dispatchEvent(propertyContext, new XFormsOptionalEvent(containingDocument, xformsControl));

        if (isAllowSendingReadonlyEvents)
            container.dispatchEvent(propertyContext, new XFormsReadwriteEvent(containingDocument, xformsControl));

        if (isAllowSendingValidEvents)
            container.dispatchEvent(propertyContext, new XFormsValidEvent(containingDocument, xformsControl));
    }

    public static class EventSchedule {

        public static final int VALUE = 1;
        public static final int REQUIRED = 2;
        public static final int RELEVANT = 4;
        public static final int READONLY = 8;
        public static final int VALID = 16;

        public static final int RELEVANT_BINDING = 32;

        public static final int ALL = VALUE | REQUIRED | RELEVANT | READONLY | VALID;

        private String effectiveControlId;
        private int type;
        private XFormsControl xformsControl;

        /**
         * Regular constructor.
         */
        public EventSchedule(String effectiveControlId, int type) {
            this.effectiveControlId = effectiveControlId;
            this.type = type;
        }

        /**
         * This special constructor allows passing an XFormsControl we know will become obsolete. This is currently the
         * only way we have to dispatch events to controls that have "disappeared".
         */
        public EventSchedule(String effectiveControlId, int type, XFormsControl xformsControl) {
            this(effectiveControlId, type);
            this.xformsControl = xformsControl;
        }

        public void updateType(int type) {
            if (this.type == RELEVANT_BINDING) {
                // NOP: all events will be sent
            } else {
                // Combine with existing events
                this.type |= type;
            }
        }

        public int getType() {
            return type;
        }

        public String getEffectiveControlId() {
            return effectiveControlId;
        }

        public XFormsControl getXFormsControl() {
            return xformsControl;
        }
    }
}
