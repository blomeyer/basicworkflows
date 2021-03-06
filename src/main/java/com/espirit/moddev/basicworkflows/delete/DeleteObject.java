/*-
 * ========================LICENSE_START=================================
 * BasicWorkflows Module
 * %%
 * Copyright (C) 2012 - 2018 e-Spirit AG
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package com.espirit.moddev.basicworkflows.delete;

import com.espirit.moddev.basicworkflows.util.Dialog;
import com.espirit.moddev.basicworkflows.util.FsLocale;
import com.espirit.moddev.basicworkflows.util.WorkflowConstants;

import de.espirit.common.base.Logging;
import de.espirit.firstspirit.access.AccessUtil;
import de.espirit.firstspirit.access.BaseContext;
import de.espirit.firstspirit.access.ReferenceEntry;
import de.espirit.firstspirit.access.ServerActionHandle;
import de.espirit.firstspirit.access.store.DeleteProgress;
import de.espirit.firstspirit.access.store.ElementDeletedException;
import de.espirit.firstspirit.access.store.IDProvider;
import de.espirit.firstspirit.access.store.LockException;
import de.espirit.firstspirit.access.store.ReleaseProgress;
import de.espirit.firstspirit.access.store.Store;
import de.espirit.firstspirit.access.store.StoreElement;
import de.espirit.firstspirit.access.store.StoreElementFilter;
import de.espirit.firstspirit.access.store.contentstore.Content2;
import de.espirit.firstspirit.access.store.contentstore.ContentWorkflowable;
import de.espirit.firstspirit.access.store.mediastore.Media;
import de.espirit.firstspirit.access.store.mediastore.MediaFolder;
import de.espirit.firstspirit.access.store.pagestore.Page;
import de.espirit.firstspirit.access.store.pagestore.PageFolder;
import de.espirit.firstspirit.access.store.sitestore.DocumentGroup;
import de.espirit.firstspirit.access.store.sitestore.PageRef;
import de.espirit.firstspirit.access.store.sitestore.PageRefFolder;
import de.espirit.firstspirit.access.store.sitestore.StartNode;
import de.espirit.firstspirit.access.store.templatestore.WorkflowScriptContext;
import de.espirit.firstspirit.server.storemanagement.ReleaseFailedException;
import de.espirit.or.Session;
import de.espirit.or.schema.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import static de.espirit.firstspirit.access.store.StoreElementFilter.on;

/**
 * This class is used to delete IDProvider/Entity objects.
 *
 * @author stephan
 * @since 1.0
 */
public class DeleteObject {

    /**
     * The Entity to be deleted.
     */
    private Entity entity;
    /**
     * The idProvider to be deleted.
     */
    private IDProvider idProvider;
    /**
     * The workflowScriptContext from the workflow.
     */
    private WorkflowScriptContext workflowScriptContext;
    /**
     * The result of the delete operation, defaults to successful.
     */
    private boolean result = true;
    /**
     * The logging class to use.
     */
    public static final Class<?> LOGGER = DeleteObject.class;
    /**
     * Exception message.
     */
    public static final String EXCEPTION = "Exception during Delete of ";
    /**
     * ID message.
     */
    public static final String ID = "  id:";
    /**
     * Name for variable that holds the objects to release.
     */
    public static final String REL_OBJECTS = "releaseObjects";
    /**
     * Name for variable that holds the objects to delete.
     */
    public static final String DEL_OBJECTS = "deleteObjects";
    /**
     * List of objects that should be deleted.
     */
    private List<IDProvider> deleteObjects = new ArrayList<IDProvider>();
    /**
     * List of objects that should be released.
     */
    private List<IDProvider> releaseObjects = new ArrayList<IDProvider>();


    /**
     * Constructor for DeleteObject.
     *
     * @param workflowScriptContext The workflowScriptContext from the workflow.
     */
    public DeleteObject(WorkflowScriptContext workflowScriptContext) {

        this.workflowScriptContext = workflowScriptContext;
        // check if content2 object
        if (workflowScriptContext.getWorkflowable() != null && workflowScriptContext.getWorkflowable() instanceof ContentWorkflowable) {
            ContentWorkflowable contentWorkflowable = (ContentWorkflowable) workflowScriptContext.getWorkflowable();
            entity = contentWorkflowable.getEntity();
        } else {
            idProvider = workflowScriptContext.getElement();
        }
    }


    /**
     * Main delete method, that distinguishes from IDProvider and Entity.
     *
     * @param checkOnly Determines if the method should do only a check or really delete the object.
     * @return true if successful.
     */
    public boolean delete(boolean checkOnly) {
        /* delete entity
           entities cannot be locked, so skip test case
         */
        if (entity != null && !checkOnly) {
            deleteEntity();
        } else if (idProvider != null) {
            deleteIDProvider(checkOnly);
        }
        return result;
    }


    /**
     * Convenience method to delete entities in current/release state.
     */
    private void deleteEntity() {
        final ContentWorkflowable contentWorkflowable = (ContentWorkflowable) workflowScriptContext.getWorkflowable();
        final Content2 content2 = contentWorkflowable.getContent();

        // delete current state
        try {
            Session session = entity.getSession();
            session.delete(entity);
            session.commit();
        } catch (Exception e) {
            Logging.logError(EXCEPTION + entity, e, LOGGER);

        } finally {
            try {
                // lock/unlock content2 to force a refresh
                content2.setLock(true, false);
                content2.save();
                content2.setLock(false, false);
            } catch (LockException e) {
                Logging.logError(EXCEPTION + entity, e, LOGGER);
            } catch (ElementDeletedException e) {
                Logging.logError(EXCEPTION + entity, e, LOGGER);
            }
        }

        // delete release state
        Session releaseSession = content2.getTemplate().getSchema().getSession(true);
        //delete page entity (release state)
        releaseSession.rollback();
        Entity entityRelease = releaseSession.find(entity.getKeyValue());
        if (entityRelease != null) {
            // if in release store
            try {
                releaseSession.delete(entityRelease);
                releaseSession.commit();
            } catch (Exception e) {
                Logging.logError(EXCEPTION + entityRelease, e, LOGGER);
                result = false;
            }
        }
    }


    /**
     * Handler for deleting IDProvider objects, including release (for webedit workflows).
     *
     * @param checkOnly Determines if the method should do only a check or really delete the object.
     */
    private void deleteIDProvider(boolean checkOnly) {
        Logging.logInfo("deleteIDProvider(" + checkOnly + ")", getClass());
        Map<String, List<IDProvider>> elementList = getDeleteElements();
        final List<IDProvider> listOfObjectsToDelete = elementList.get(DEL_OBJECTS);
        if (checkOnly) {
            List<IDProvider> lockedElements = new ArrayList<IDProvider>();
            if (listOfObjectsToDelete != null) {
                for (IDProvider idProv : listOfObjectsToDelete) {
                    if (idProv.isLockedOnServer(true) && !idProv.isLocked()) {
                        // element is locked on server from different session, delete not possible
                        lockedElements.add(idProv);
                        result = false;
                    }
                }
            }
            if (elementList.get(REL_OBJECTS) != null) {
                for (IDProvider idProv : elementList.get(REL_OBJECTS)) {
                    if (idProv.isLockedOnServer(true) && !idProv.isLocked()) {
                        // element is locked on server from different session, delete not possible
                        result = false;
                        lockedElements.add(idProv);
                    }
                }
            }
            storeReferences(lockedElements);
        } else {
            final boolean listContainsItems = listOfObjectsToDelete != null && !listOfObjectsToDelete.isEmpty();
            Logging.logInfo("listContainsItems: " + listContainsItems, getClass());
            if (listContainsItems) {
                deleteElements(listOfObjectsToDelete);
            }
            if (elementList.get(REL_OBJECTS) != null && !elementList.get(REL_OBJECTS).isEmpty()) {
                releaseElements(elementList.get(REL_OBJECTS));
            }
        }
    }

    /**
     * Convenience method to delete IDProvider objects.
     *
     * @param deleteObjects The list of IDProvider objects to delete.
     */
    private void deleteElements(List<IDProvider> deleteObjects) {

        Logging.logInfo("deleteElements: " + deleteObjects.size(), getClass());

        // lock workflow element first
        try {
            workflowScriptContext.getElement().setLock(false, false);
        } catch (LockException e) {
            Logging.logError(EXCEPTION + idProvider, e, LOGGER);
        } catch (ElementDeletedException e) {
            Logging.logError(EXCEPTION + idProvider, e, LOGGER);
        }

        // delete elements
        ServerActionHandle<? extends DeleteProgress, Boolean> handle = deleteIgnoringReferences(deleteObjects);
        if (handle != null) {
            try {
                handle.checkAndThrow();
                result = handle.getResult();
                Logging.logInfo("Delete Result: " + result, LOGGER);
                final DeleteProgress progress = handle.getProgress(true);
                final Set<Long> lockedFailed = progress.getLockFailedElements();
                final Set<Long> missingPermission = progress.getMissingPermissionElements();
                Logging.logInfo("Deleted Elements:", LOGGER);
                for (Long deleted : progress.getDeletedElements()) {
                    Logging.logInfo(ID + deleted, LOGGER);
                }
                if (lockedFailed != null && !lockedFailed.isEmpty()) {
                    Logging.logInfo("LockFailedElements:", LOGGER);
                    for (Long locked : lockedFailed) {
                        Logging.logInfo(ID + locked, LOGGER);
                    }
                }
                if (missingPermission != null && !missingPermission.isEmpty()) {
                    Logging.logInfo("MissingPermissionElement", LOGGER);
                    for (Long missing : missingPermission) {
                        Logging.logInfo(ID + missing, LOGGER);
                    }
                }
            } catch (Exception e) {
                Logging.logError(EXCEPTION + idProvider, e, LOGGER);
            }
        } else {
            Logging.logWarning("ServerActionHandle for DeleteProgress is null!", getClass());
        }
        workflowScriptContext.getTask().closeTask();
        workflowScriptContext.getElement().refresh();
    }

    // Needed in Tests
    protected ServerActionHandle<? extends DeleteProgress, Boolean> deleteIgnoringReferences(final List<IDProvider> deleteObjects) {
        return AccessUtil.delete(deleteObjects, true);
    }


    /**
     * Convenience method to release IDProvider objects (used in webedit context).
     *
     * @param releaseObjects The list of IDProvider objects to release.
     */
    private void releaseElements(List<IDProvider> releaseObjects) {
        // release parent elements (only used in webedit workflow)
        ServerActionHandle<? extends ReleaseProgress, Boolean> releaseHandle;
        for (IDProvider idProv : releaseObjects) {
            releaseHandle = releaseWithAccessibilityAndNewOnly(idProv);
            if (releaseHandle != null) {
                try {
                    releaseHandle.checkAndThrow();
                    releaseHandle.getResult();
                    result = releaseHandle.getResult();
                    Logging.logInfo("Release Result: " + result, LOGGER);
                    final ReleaseProgress progress = releaseHandle.getProgress(true);
                    final Set<Long> lockedFailed = progress.getLockFailedElements();
                    final Set<Long> missingPermission = progress.getMissingPermissionElements();
                    Logging.logInfo("Released Elements:", LOGGER);
                    for (Long released : progress.getReleasedElements()) {
                        idProv.refresh();
                        Logging.logInfo(ID + released, LOGGER);
                    }
                    if (lockedFailed != null && !lockedFailed.isEmpty()) {
                        Logging.logInfo("LockFailedElements:", LOGGER);
                        for (Long locked : lockedFailed) {
                            Logging.logInfo(ID + locked, LOGGER);
                        }
                    }
                    if (missingPermission != null && !missingPermission.isEmpty()) {
                        Logging.logInfo("MissingPermissionElement", LOGGER);

                        for (Long missing : missingPermission) {
                            Logging.logInfo(ID + missing, LOGGER);
                        }
                    }
                } catch (ReleaseFailedException e) {
                    Dialog dialog = new Dialog(workflowScriptContext);

                    ResourceBundle.clearCache();
                    final ResourceBundle bundle = ResourceBundle.getBundle(WorkflowConstants.MESSAGES, new FsLocale(workflowScriptContext).get());

                    dialog.showError(bundle.getString("permissionIssues"), bundle.getString("missingPermissions"));

                    Logging.logError("Exception during Release of " + idProv, e, LOGGER);
                } catch (Exception e) {
                    Logging.logError("Exception during Release of " + idProv, e, LOGGER);
                }
            }
            // release new startnode (if modified through delete action)
            if (idProv instanceof PageRefFolder) {
                StartNode startNode = ((PageRefFolder) idProv).getStartNode();
                if (startNode != null && startNode.getReleaseStatus() != IDProvider.RELEASED) {
                    List<IDProvider> startNodeList = new ArrayList<IDProvider>();
                    startNodeList.add(startNode);
                    releaseElements(startNodeList);
                }
            }

        }
    }

    protected ServerActionHandle<? extends ReleaseProgress, Boolean> releaseWithAccessibilityAndNewOnly(final IDProvider idProv) {
        return AccessUtil.release(idProv, false, true, false, IDProvider.DependentReleaseType.DEPENDENT_RELEASE_NEW_ONLY);
    }


    /**
     * Gets delete and release elements, as the result is different for pagerefs in JC/Webedit. In case of JC: The element will be deleted. The parent
     * folder in the page-/sitestore are released. In case of Webedit: The pageref will be deleted. If the pageref is the last node in the folder, the
     * folder is deleted. The page will be deleted if it is no longer used. If the page is the last node in the folder, the folder is deleted
     * (up-recursive). The parent folder in the page-/sitestore are released.
     *
     * @return a map with IDProvider objects to delete.
     */
    private Map<String, List<IDProvider>> getDeleteElements() {
        Map<String, List<IDProvider>> deleteElements = new HashMap<String, List<IDProvider>>();

        // Webedit
        if (workflowScriptContext.is(BaseContext.Env.WEBEDIT)) {
            if (idProvider instanceof PageRef) {
                // add Page and PageFolder if no longer referenced
                regardPageStore();
                // add current PageRefStore element and PageRefFolders (up-recursive)
                regardPageRefStore();
            }
            if (idProvider instanceof DocumentGroup) {
                // add current PageRefStore element and PageRefFolders (up-recursive)
                regardPageRefStore();
            }
            //Added for media management in CC (since FS 5.2)
            if (idProvider instanceof Media || idProvider instanceof MediaFolder) {
                //false == don't delete parent folder, see FSFIVE-53
                final boolean deleteEmptyParent = true;
                // add current MediaElement element and MediaFolder (up-recursive)
                regardMediaStore(deleteEmptyParent);
            }
        } else {
            // JC
            deleteObjects.add(idProvider);

            if (idProvider.getStore().getType() != Store.Type.TEMPLATESTORE) {
                // release parent folder
                releaseObjects.add(idProvider.getParent());
            }
        }
        deleteElements.put(DEL_OBJECTS, deleteObjects);
        deleteElements.put(REL_OBJECTS, releaseObjects);

        return deleteElements;
    }

    /**
     * This method adds the current PageRefStore element and PageFolder to the elements that should be deleted if they are no longer referenced.
     */
    private void regardPageRefStore() {
        // add current element
        deleteObjects.add(idProvider);
        // add PagerefFolder (up-recursive) if no child elements are present
        final StoreElementFilter filter = on(PageRefFolder.class, PageRef.class, DocumentGroup.class);
        checkParentPath(idProvider, filter);
    }

    /**
     * This method adds the Page and PageFolder to the elements that should be deleted if they are no longer referenced.
     */
    private void regardPageStore() {
        IDProvider element = ((PageRef) idProvider).getPage();
        // only referenced in pageref that will be deleted
        if (element.getIncomingReferences().length == 1) {
            // add page
            deleteObjects.add(element);
            // delete PageFolder if last child is being deleted
            final StoreElementFilter filter = on(PageFolder.class, Page.class);
            checkParentPath(element, filter);
        }
    }

    /**
     * New since media management in CC added in FS 5.2.
     */
    private void regardMediaStore(final boolean deleteEmptyParent) {
        deleteObjects.add(idProvider);
        if (deleteEmptyParent) {
            // add Media (up-recursive) if no child elements are present
            final StoreElementFilter filter = on(MediaFolder.class, Media.class);
            checkParentPath(idProvider, filter);
        }
    }

    private void checkParentPath(final IDProvider childElement, final StoreElementFilter filter) {
        IDProvider element = childElement;
        while (element.getParent() != null) {
            element = element.getParent();
            final ReferenceEntry[] incomingReferences = element.getIncomingReferences();
            // only use elements that are no longer in use
            if(incomingReferences.length == 0) {
                Logging.logInfo("Checking parent element " + element.getUid() + " of child element " + childElement.getUid(), LOGGER);
                Iterator<StoreElement> iter = element.getChildren(filter, false).iterator();
                // folder has at least one element -- our child
                iter.next();
                // check if there are more
                if (!iter.hasNext()) {
                    Logging.logInfo("parent element has no children - add to delete objects", LOGGER);
                    deleteObjects.add(element);
                } else {
                    Logging.logInfo("parent element has children - abort", LOGGER);
                    releaseObjects.add(element);
                    break;
                }
            } else {
                Logging.logInfo("element has incoming references - abort", LOGGER);
                releaseObjects.add(element);
                break;
            }
        }
    }


    /**
     * Convenience method to store uid names of objects that reference the object to be deleted in the workflow session.
     *
     * @param lckObjects A list of IDProvider objects that reference the object to be deleted.
     */
    public void storeReferences(List<IDProvider> lckObjects) {
        List<List<String>> lockedObjects = new ArrayList<List<String>>();
        for (IDProvider idProv : lckObjects) {
            List<String> lockedObjectList = new ArrayList<String>();
            lockedObjectList.add(idProv.getElementType());
            if (idProv.hasUid()) {
                lockedObjectList.add(idProv.getUid());
            } else {
                lockedObjectList.add(idProv.getName());
            }
            lockedObjects.add(lockedObjectList);
        }
        // put referenced objects to session for further use
        workflowScriptContext.getSession().put("wfLockedObjects", lockedObjects);
    }
}
