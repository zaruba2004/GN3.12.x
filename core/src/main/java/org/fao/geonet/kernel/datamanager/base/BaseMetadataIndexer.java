//=============================================================================
//===	Copyright (C) 2001-2022 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.kernel.datamanager.base;

import java.util.concurrent.TimeUnit;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import jeeves.server.dispatchers.ServiceManager;
import jeeves.xlink.Processor;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.api.records.attachments.Store;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.AbstractMetadata;
import org.fao.geonet.domain.Constants;
import org.fao.geonet.domain.Group;
import org.fao.geonet.domain.InspireAtomFeed;
import org.fao.geonet.domain.MetadataCategory;
import org.fao.geonet.domain.MetadataStatus;
import org.fao.geonet.domain.MetadataStatus_;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.MetadataValidation;
import org.fao.geonet.domain.MetadataValidationStatus;
import org.fao.geonet.domain.OperationAllowed;
import org.fao.geonet.domain.OperationAllowedId;
import org.fao.geonet.domain.ReservedGroup;
import org.fao.geonet.domain.ReservedOperation;
import org.fao.geonet.domain.StatusValueType;
import org.fao.geonet.domain.User;
import org.fao.geonet.domain.userfeedback.RatingsSetting;
import org.fao.geonet.events.history.RecordDeletedEvent;
import org.fao.geonet.events.md.MetadataIndexCompleted;
import org.fao.geonet.kernel.GeonetworkDataDirectory;
import org.fao.geonet.kernel.IndexMetadataTask;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.kernel.SelectionManager;
import org.fao.geonet.kernel.SvnManager;
import org.fao.geonet.kernel.XmlSerializer;
import org.fao.geonet.kernel.datamanager.IMetadataIndexer;
import org.fao.geonet.kernel.datamanager.IMetadataManager;
import org.fao.geonet.kernel.datamanager.IMetadataUtils;
import org.fao.geonet.kernel.datamanager.draft.DraftMetadataIndexer;
import org.fao.geonet.kernel.search.ISearchManager;
import org.fao.geonet.kernel.search.SearchManager;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.kernel.setting.Settings;
import org.fao.geonet.repository.GroupRepository;
import org.fao.geonet.repository.InspireAtomFeedRepository;
import org.fao.geonet.repository.MetadataStatusRepository;
import org.fao.geonet.repository.MetadataValidationRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.repository.userfeedback.UserFeedbackRepository;
import org.fao.geonet.resources.Resources;
import org.fao.geonet.util.ThreadUtils;
import org.fao.geonet.utils.Log;
import org.jdom.Attribute;
import org.jdom.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Metadata indexer responsible for updating index in a background executor.
 *
 * Helper method exist to schedule records for reindex by id. These methods make use of the service context
 * of the current thread if needed to access user session.
 *
 * This class maintains its own service context for use in the background, and does not have access
 * to a user session.
 */
public class BaseMetadataIndexer implements IMetadataIndexer, ApplicationEventPublisherAware {

    Lock waitLoopLock = new ReentrantLock();
    Lock indexingLock = new ReentrantLock();

    @Autowired
    private SearchManager searchManager;
    @Autowired
    private GeonetworkDataDirectory geonetworkDataDirectory;
    @Autowired
    protected MetadataStatusRepository statusRepository;

    private IMetadataUtils metadataUtils;
    private IMetadataManager metadataManager;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OperationAllowedRepository operationAllowedRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private MetadataValidationRepository metadataValidationRepository;
    @Autowired
    private SchemaManager schemaManager;
    @Autowired(required = false)
    private SvnManager svnManager;
    @Autowired
    private InspireAtomFeedRepository inspireAtomFeedRepository;
    @Autowired(required = false)
    private XmlSerializer xmlSerializer;
    @Autowired
    @Lazy
    private SettingManager settingManager;
    @Autowired
    private UserFeedbackRepository userFeedbackRepository;
    @Autowired
    @Qualifier("resourceStore")
    private Store store;
    @Autowired
    private Resources resources;

    private ApplicationEventPublisher publisher;

    /** Private service context managed by service init / destroy for use by metadata indexing tasks. */
    private ServiceContext indexMetadataTaskContext;

    public BaseMetadataIndexer() {
    }

    public void init(ServiceContext context) throws Exception {
        ServiceManager serviceManager = context.getBean(ServiceManager.class);
        if( indexMetadataTaskContext == null ) {
            indexMetadataTaskContext = serviceManager.createServiceContext("_indexMetadataTask", context);
        } else {
            context.getLogger().debug("Metadata Indexer already initialized");
        }
    }

    public void destroy(){
        if (indexMetadataTaskContext != null) {
            indexMetadataTaskContext.clear();
            indexMetadataTaskContext = null;
        }
    }

    @Override
    public void setMetadataUtils(IMetadataUtils metadataUtils) {
        this.metadataUtils = metadataUtils;
    }

    @Override
    public void setMetadataManager(IMetadataManager metadataManager) {
        this.metadataManager = metadataManager;
    }

    Set<String> waitForIndexing = new HashSet<String>();
    Set<String> indexing = new HashSet<String>();
    Set<IndexMetadataTask> batchIndex = new ConcurrentHashSet<IndexMetadataTask>();

    @Override
    public void forceIndexChanges() throws IOException {
        searchManager.forceIndexChanges();
    }

    @Override
    public int batchDeleteMetadataAndUpdateIndex(Specification<? extends AbstractMetadata> specification)
        throws Exception {
        final List<? extends AbstractMetadata> metadataToDelete = metadataUtils.findAll(specification);


        // Remove records from the database
        // Delete all works on a database created by hibernate
        // (because some foreign constraints are missing.
        // See https://github.com/geonetwork/core-geonetwork/issues/1863). FIXME
        // Delete all does not work on older database
        // where operationAllowed contains references to the metadata table.
        //
//        for (AbstractMetadata md : metadataToDelete) {
//            // --- remove metadata directory for each record
//            store.delResources(ServiceContext.get(), md.getUuid(), true);
//        }
//
//        // Remove records from the index
//        searchManager.delete(metadataToDelete.stream().map(input -> Integer.toString(input.getId())).collect(Collectors.toList()));
//        metadataManager.deleteAll(specification);
        // So delete one by one even if slower
        metadataToDelete.forEach(md -> {
            try {
                // Extract information for RecordDeletedEvent
                LinkedHashMap<String, String> titles = metadataUtils.extractTitles(Integer.toString(md.getId()));
                UserSession userSession = ServiceContext.get().getUserSession();
                String xmlBefore = md.getData();

                store.delResources(ServiceContext.get(), md.getUuid());
                metadataManager.deleteMetadata(ServiceContext.get(), String.valueOf(md.getId()));

                // Trigger RecordDeletedEvent
                new RecordDeletedEvent(md.getId(), md.getUuid(), titles, userSession.getUserIdAsInt(), xmlBefore).publish(ApplicationContextHolder.get());
            } catch (Exception e) {
                Log.warning(Geonet.DATA_MANAGER, String.format(

                    "Error during removal of metadata %s part of batch delete operation. " +
                    "This error may create a ghost record (ie. not in the index " +
                    "but still present in the database). " +
                    "You can reindex the catalogue to see it again. " +
                    "Error was: %s.", md.getUuid(), e.getMessage()));
                e.printStackTrace();
            }
        });

        return metadataToDelete.size();
    }

    @Override
    /**
     * Search for all records having XLinks (ie. indexed with _hasxlinks flag),
     * clear the cache and reindex all records found.
     */
    public synchronized void rebuildIndexXLinkedMetadata(final ServiceContext context) throws Exception {

        // get all metadata with XLinks
        Set<Integer> toIndex = searchManager.getDocsWithXLinks();

        if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
            Log.debug(Geonet.DATA_MANAGER, "Will index " + toIndex.size() + " records with XLinks");
        if (toIndex.size() > 0) {
            // clean XLink Cache so that cache and index remain in sync
            Processor.clearCache();

            ArrayList<String> stringIds = new ArrayList<String>();
            for (Integer id : toIndex) {
                stringIds.add(id.toString());
            }
            // execute indexing operation
            batchIndexInThreadPool(stringIds);
        }
    }

    /**
     * Reindex all records in current selection.
     */
    @Override
    public synchronized void rebuildIndexForSelection(final ServiceContext context, String bucket, boolean clearXlink)
        throws Exception {

        // get all metadata ids from selection
        ArrayList<String> listOfIdsToIndex = new ArrayList<String>();
        UserSession session = context.getUserSession();
        SelectionManager sm = SelectionManager.getManager(session);

        synchronized (sm.getSelection(bucket)) {
            for (Iterator<String> iter = sm.getSelection(bucket).iterator(); iter.hasNext(); ) {
                String uuid = (String) iter.next();
                String id = metadataUtils.getMetadataId(uuid);
                if (id != null) {
                    listOfIdsToIndex.add(id);
                }
            }
        }

        if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
            Log.debug(Geonet.DATA_MANAGER, "Will index " + listOfIdsToIndex.size() + " records from selection.");
        }

        if (listOfIdsToIndex.size() > 0) {
            // clean XLink Cache so that cache and index remain in sync
            if (clearXlink) {
                Processor.clearCache();
            }

            // execute indexing operation
            batchIndexInThreadPool(listOfIdsToIndex);
        }
    }

    /**
     * Index multiple metadata in a separate thread. Wait until the current
     * transaction commits before starting threads (to make sure that all metadata
     * are committed).
     *
     * @param metadataIds the metadata ids to index
     */
    @Override
    public void batchIndexInThreadPool( List<?> metadataIds) {

        TransactionStatus transactionStatus = null;
        try {
            transactionStatus = TransactionAspectSupport.currentTransactionStatus();
        } catch (NoTransactionException e) {
            // not in a transaction so we can go ahead.
        }
        // split reindexing task according to number of processors we can assign
        int threadCount = ThreadUtils.getNumberOfThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        int perThread;
        if (metadataIds.size() < threadCount)
            perThread = metadataIds.size();
        else
            perThread = metadataIds.size() / threadCount;
        int index = 0;
        if (Log.isDebugEnabled(Geonet.INDEX_ENGINE)) {
            Log.debug(Geonet.INDEX_ENGINE, "Indexing " + metadataIds.size() + " records.");
            Log.debug(Geonet.INDEX_ENGINE, metadataIds.toString());
        }
        AtomicInteger numIndexedTracker = new AtomicInteger();



        while (index < metadataIds.size()) {
            int start = index;
            int count = Math.min(perThread, metadataIds.size() - start);
            int nbRecords = start + count;

            if (Log.isDebugEnabled(Geonet.INDEX_ENGINE)) {
                Log.debug(Geonet.INDEX_ENGINE, "Indexing records from " + start + " to " + nbRecords);
            }

            List<?> subList = metadataIds.subList(start, nbRecords);

            if (Log.isDebugEnabled(Geonet.INDEX_ENGINE)) {
                Log.debug(Geonet.INDEX_ENGINE, subList.toString());
            }

            // create threads to process this chunk of ids
            Runnable worker = new IndexMetadataTask(indexMetadataTaskContext, subList, batchIndex, transactionStatus, numIndexedTracker);
            executor.execute(worker);
            index += count;
        }
        // let the started threads finish in the background and then clean up executor
        executor.shutdown();
    }

    @Override
    public boolean isIndexing() {
        indexingLock.lock();
        try {
            return !indexing.isEmpty() || !batchIndex.isEmpty();
        } finally {
            indexingLock.unlock();
        }
    }

    @Override
    public void indexMetadata(final List<String> metadataIds) throws Exception {
        for (String metadataId : metadataIds) {
            indexMetadata(metadataId, false, null);
        }

        searchManager.forceIndexChanges();
    }

    @Override
    public void indexMetadata(final String metadataId, boolean forceRefreshReaders, ISearchManager searchManager)
        throws Exception {
        waitLoopLock.lock();
        try {
            if (waitForIndexing.contains(metadataId)) {
                return;
            }
            while (indexing.contains(metadataId)) {
                try {
                    waitForIndexing.add(metadataId);
                    // don't index the same metadata 2x
                    synchronized (this) {
                        wait(200);
                    }
                } catch (InterruptedException e) {
                    return;
                } finally {
                    waitForIndexing.remove(metadataId);
                }
            }
            indexingLock.lock();
            try {
                indexing.add(metadataId);
            } finally {
                indexingLock.unlock();
            }
        } finally {
            waitLoopLock.unlock();
        }
        AbstractMetadata fullMd;

        if (searchManager == null) {
            searchManager = getServiceContext().getBean(SearchManager.class);
        }

        try {
            Vector<Element> moreFields = new Vector<Element>();
            int id = Integer.parseInt(metadataId);

            // get metadata, extracting and indexing any xlinks
            Element md = getXmlSerializer().selectNoXLinkResolver(metadataId, true, false);
            if (getXmlSerializer().resolveXLinks()) {
                List<Attribute> xlinks = Processor.getXLinks(md);
                if (xlinks.size() > 0) {
                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.HASXLINKS, "1", true, true));
                    for (Attribute xlink : xlinks) {
                        moreFields.add(
                            SearchManager.makeField(Geonet.IndexFieldNames.XLINK, xlink.getValue(), true, true));
                    }
                    Processor.detachXLink(md, getServiceContext());
                } else {
                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.HASXLINKS, "0", true, true));
                }
            } else {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.HASXLINKS, "0", true, true));
            }

            fullMd = metadataUtils.findOne(id);
            if( fullMd == null){
                // Metadata record has been subsequently deleted
                searchManager.delete(metadataId);
                return;
            }
            final String schema = fullMd.getDataInfo().getSchemaId();
            final String createDate = fullMd.getDataInfo().getCreateDate().getDateAndTime();
            final String changeDate = fullMd.getDataInfo().getChangeDate().getDateAndTime();
            final String source = fullMd.getSourceInfo().getSourceId();
            final MetadataType metadataType = fullMd.getDataInfo().getType();
            final String root = fullMd.getDataInfo().getRoot();
            final String uuid = fullMd.getUuid();
            final String extra = fullMd.getDataInfo().getExtra();
            final String isHarvested = String
                .valueOf(Constants.toYN_EnabledChar(fullMd.getHarvestInfo().isHarvested()));
            final String owner = String.valueOf(fullMd.getSourceInfo().getOwner());
            final Integer groupOwner = fullMd.getSourceInfo().getGroupOwner();
            final String popularity = String.valueOf(fullMd.getDataInfo().getPopularity());
            final String rating = String.valueOf(fullMd.getDataInfo().getRating());
            final String displayOrder = fullMd.getDataInfo().getDisplayOrder() == null ? null
                : String.valueOf(fullMd.getDataInfo().getDisplayOrder());

            if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                Log.debug(Geonet.DATA_MANAGER, "record schema (" + schema + ")"); // DEBUG
                Log.debug(Geonet.DATA_MANAGER, "record createDate (" + createDate + ")"); // DEBUG
            }

            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.ROOT, root, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.SCHEMA, schema, true, true));
            moreFields
                .add(SearchManager.makeField(Geonet.IndexFieldNames.DATABASE_CREATE_DATE, createDate, true, true));
            moreFields
                .add(SearchManager.makeField(Geonet.IndexFieldNames.DATABASE_CHANGE_DATE, changeDate, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.SOURCE, source, true, true));
            moreFields.add(
                SearchManager.makeField(Geonet.IndexFieldNames.IS_TEMPLATE, metadataType.codeString, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.UUID, uuid, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.IS_HARVESTED, isHarvested, true, true));

            if (fullMd.getHarvestInfo().isHarvested()) {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.HARVESTUUID, fullMd.getHarvestInfo().getUuid(), true, true));
            }

            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.OWNER, owner, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.DUMMY, "0", false, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.POPULARITY, popularity, true, true));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.RATING, rating, true, true));
            if (RatingsSetting.ADVANCED.equals(settingManager.getValue(Settings.SYSTEM_LOCALRATING_ENABLE))) {
                int nbOfFeedback = userFeedbackRepository.findByMetadata_Uuid(uuid).size();
                moreFields.add(
                    SearchManager.makeField(Geonet.IndexFieldNames.FEEDBACKCOUNT, nbOfFeedback + "", true, true));
            }
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.DISPLAY_ORDER, displayOrder, true, false));
            moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.EXTRA, extra, false, true));

            // If the metadata has an atom document, index related information
            InspireAtomFeed feed = inspireAtomFeedRepository.findByMetadataId(id);

            if ((feed != null) && StringUtils.isNotEmpty(feed.getAtom())) {
                moreFields.add(SearchManager.makeField("has_atom", "y", true, true));
                moreFields.add(SearchManager.makeField("any", feed.getAtom(), false, true));
            }

            if (owner != null) {
                User user = userRepository.findOne(fullMd.getSourceInfo().getOwner());
                if (user != null) {
                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.USERINFO, user.getUsername() + "|"
                        + user.getSurname() + "|" + user.getName() + "|" + user.getProfile(), true, false));
                    moreFields.add(SearchManager.makeField(
                        Geonet.IndexFieldNames.OWNERNAME,
                        user.getName() + " " + user.getSurname(),
                        true, true));
                }
            }

            String logoUUID = null;
            if (groupOwner != null) {
                final Group group = groupRepository.findOne(groupOwner);
                if (group != null) {
                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.GROUP_OWNER,
                        String.valueOf(groupOwner), true, true));
                    final boolean preferGroup = settingManager.getValueAsBool(Settings.SYSTEM_PREFER_GROUP_LOGO, true);
                    if (group.getWebsite() != null && !group.getWebsite().isEmpty() && preferGroup) {
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.GROUP_WEBSITE, group.getWebsite(),
                            true, false));
                    }
                    if (group.getLogo() != null && preferGroup) {
                        logoUUID = group.getLogo();
                    }
                }
            }

            // Group logo are in the harvester folder and contains extension in file name
            boolean added = false;
            if (StringUtils.isNotEmpty(logoUUID)) {
                final Path harvesterLogosDir = resources.locateHarvesterLogosDir(getServiceContext());
                try (Resources.ResourceHolder logo = resources.getImage(getServiceContext(), logoUUID, harvesterLogosDir)) {
                    if (logo != null) {
                        added = true;
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.LOGO,
                                                               "/images/harvesting/" + logo.getPath().getFileName(),
                                                               true, false));
                    }
                }
            }

            // If not available, use the local catalog logo
            if (!added) {
                logoUUID = source + ".png";
                final Path logosDir = resources.locateLogosDir(getServiceContext());
                try (Resources.ResourceHolder image = resources.getImage(getServiceContext(), logoUUID, logosDir)) {
                    if (image != null) {
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.LOGO, "/images/logos/" + logoUUID, true, false));
                    }
                }
            }

            // get privileges
            List<OperationAllowed> operationsAllowed = operationAllowedRepository.findAllById_MetadataId(id);

            boolean isPublishedToAll = false;

            for (OperationAllowed operationAllowed : operationsAllowed) {
                OperationAllowedId operationAllowedId = operationAllowed.getId();
                int groupId = operationAllowedId.getGroupId();
                int operationId = operationAllowedId.getOperationId();

                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.OP_PREFIX + operationId,
                    String.valueOf(groupId), true, true));
                if (operationId == ReservedOperation.view.getId()) {
                    Group g = groupRepository.findOne(groupId);
                    if (g != null) {
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.GROUP_PUBLISHED, g.getName(),
                            true, true));
                        if (g.getId() == ReservedGroup.all.getId()) {
                            isPublishedToAll = true;
                        }
                    }
                }
            }

            if (isPublishedToAll) {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.IS_PUBLISHED_TO_ALL, "y", true, true));
            } else {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.IS_PUBLISHED_TO_ALL, "n", true, true));
            }

            for (MetadataCategory category : fullMd.getCategories()) {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.CAT, category.getName(), true, true));
            }

            // get status
            Sort statusSort = new Sort(Sort.Direction.DESC, MetadataStatus_.changeDate.getName());
            List<MetadataStatus> statuses = statusRepository.findAllByMetadataIdAndByType(id, StatusValueType.workflow, statusSort);
            if (!statuses.isEmpty()) {
                MetadataStatus stat = statuses.get(0);
                String status = String.valueOf(stat.getStatusValue().getId());
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.STATUS, status, true, true));
                String statusChangeDate = stat.getChangeDate().getDateAndTime();
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.STATUS_CHANGE_DATE, statusChangeDate,
                    true, true));
            }


            // getValidationInfo
            // -1 : not evaluated
            // 0 : invalid
            // 1 : valid
            List<MetadataValidation> validationInfo = metadataValidationRepository.findAllById_MetadataId(id);
            if (validationInfo.isEmpty()) {
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.VALID, "-1", true, true));
            } else {
                String isValid = "1";
                boolean hasInspireValidation = false;
                for (MetadataValidation vi : validationInfo) {
                    String type = vi.getId().getValidationType();
                    MetadataValidationStatus status = vi.getStatus();

                    // TODO: Check if ignore INSPIRE validation?
                    if (!type.equalsIgnoreCase("inspire")) {
                        if (status == MetadataValidationStatus.INVALID && vi.isRequired()) {
                            isValid = "0";
                        }
                    } else {
                        hasInspireValidation = true;
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.INSPIRE_VALIDATION_DATE, vi.getValidationDate().getDateAndTime(), true, true));
                        moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.INSPIRE_REPORT_URL, vi.getReportUrl(), true, true));
                    }

                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.VALID + "_" + type, status.getCode(),
                        true, true));
                }
                moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.VALID, isValid, true, true));

                if (!hasInspireValidation) {
                    moreFields.add(SearchManager.makeField(Geonet.IndexFieldNames.VALID_INSPIRE, "-1", true, true));
                }
            }

            //To inject extra fields from BaseMetadataIndexer inherited beans
            addExtraFields(fullMd, moreFields);

            searchManager.index(schemaManager.getSchemaDir(schema), md, metadataId, moreFields, metadataType, root,
                forceRefreshReaders);

        } catch (Exception x) {
            Log.error(Geonet.DATA_MANAGER, "The metadata document index with id=" + metadataId
                + " is corrupt/invalid - ignoring it. Error: " + x.getMessage(), x);
            fullMd = null;
        } finally {
            indexingLock.lock();
            try {
                indexing.remove(metadataId);
            } finally {
                indexingLock.unlock();
            }
        }
        if (fullMd != null) {
            this.publisher.publishEvent(new MetadataIndexCompleted(fullMd));
        }
    }


    /**
     * Function to be overrided by children to add extra fields cleanly.
     * Don't forget to call always super.addExtraFields, just in case
     *
     * @param fullMd
     * @param moreFields
     */
    protected void addExtraFields(AbstractMetadata fullMd, Vector<Element> moreFields) {
        // If we are not using draft utils, mark all as "no draft"
        // needed to be compatible with UI searches that check draft existence
        if (!DraftMetadataIndexer.class.isInstance(this)) {
            moreFields.addElement(SearchManager.makeField(Geonet.IndexFieldNames.DRAFT, "n", true, true));
        }
    }

    private XmlSerializer getXmlSerializer() {
        return xmlSerializer;
    }

    /**
     * @param context
     * @param id
     * @param md
     * @throws Exception
     */
    @Override
    public void versionMetadata(ServiceContext context, String id, Element md) throws Exception {
        if (svnManager != null) {
            svnManager.createMetadataDir(id, context, md);
        }
    }

    /**
     * @param beginAt
     * @param interval
     * @throws Exception
     */
    @Override
    public void rescheduleOptimizer(Calendar beginAt, int interval) throws Exception {
        searchManager.rescheduleOptimizer(beginAt, interval);
    }

    /**
     * @throws Exception
     */
    @Override
    public void disableOptimizer() throws Exception {
        searchManager.disableOptimizer();
    }

    /**
     * Service context for the current thread if available, or the one provided during init.
     *
     * @return service context for current thread if available, or service context used during init.
     */
    protected ServiceContext getServiceContext() {
        ServiceContext context = ServiceContext.get();
        if( context != null ){
            return context; // use ServiceContext from current ThreadLocal
        }
        return indexMetadataTaskContext; // backup ServiceContext provided during init
    }

    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }
}
