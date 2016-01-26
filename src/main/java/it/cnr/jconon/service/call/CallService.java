package it.cnr.jconon.service.call;


import it.cnr.cool.cmis.model.ACLType;
import it.cnr.cool.cmis.model.CoolPropertyIds;
import it.cnr.cool.cmis.service.ACLService;
import it.cnr.cool.cmis.service.CMISService;
import it.cnr.cool.cmis.service.CacheService;
import it.cnr.cool.cmis.service.FolderService;
import it.cnr.cool.cmis.service.UserCache;
import it.cnr.cool.cmis.service.VersionService;
import it.cnr.cool.mail.MailService;
import it.cnr.cool.mail.model.EmailMessage;
import it.cnr.cool.rest.util.Util;
import it.cnr.cool.security.service.UserService;
import it.cnr.cool.security.service.impl.alfresco.CMISUser;
import it.cnr.cool.service.I18nService;
import it.cnr.cool.util.GroupsUtils;
import it.cnr.cool.util.MimeTypes;
import it.cnr.cool.util.StringUtil;
import it.cnr.cool.web.PermissionServiceImpl;
import it.cnr.cool.web.scripts.exception.ClientMessageException;
import it.cnr.jconon.cmis.model.JCONONDocumentType;
import it.cnr.jconon.cmis.model.JCONONFolderType;
import it.cnr.jconon.cmis.model.JCONONPolicyType;
import it.cnr.jconon.cmis.model.JCONONPropertyIds;
import it.cnr.jconon.repository.CallRepository;
import it.cnr.jconon.service.TypeService;
import it.cnr.jconon.service.cache.CompetitionFolderService;
import it.cnr.jconon.service.helpdesk.HelpdeskService;
import it.spasia.opencmis.criteria.Criteria;
import it.spasia.opencmis.criteria.CriteriaFactory;
import it.spasia.opencmis.criteria.restrictions.Restrictions;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CallService implements UserCache, InitializingBean {
    public final static String FINAL_APPLICATION = "Domande definitive",
    		FINAL_SCHEDE = "Schede di valutazione",
            DOMANDA_INIZIALE = "I",
            DOMANDA_CONFERMATA = "C",
            DOMANDA_PROVVISORIA = "P";
    private static final Logger LOGGER = LoggerFactory.getLogger(CallService.class);
    public static String BANDO_NAME = "BANDO ";
    public static String GROUP_COMMISSIONI_CONCORSO = "GROUP_COMMISSIONI_CONCORSO",
    		GROUP_RDP_CONCORSO = "GROUP_RDP_CONCORSO",
            GROUP_CONCORSI = "GROUP_CONCORSI",
            GROUP_EVERYONE = "GROUP_EVERYONE";
    @Autowired
    private CMISService cmisService;
    @Autowired
    private PermissionServiceImpl permission;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private I18nService i18NService;
    @Autowired
    private CompetitionFolderService competitionFolderService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private VersionService versionService;
    @Autowired
    private ACLService aclService;
    private Cache<String, String> cache;
    @Autowired
    private MailService mailService;
    @Autowired
    private UserService userService;
    @Autowired
    private TypeService typeService;
    @Autowired
    private HelpdeskService helpdeskService;
	@Autowired
	private ApplicationContext context;
	@Autowired
    private CallRepository callRepository;

    public Folder getMacroCall(Session cmisSession, Folder call) {
        Folder currCall = call;
        while (currCall != null && typeService.hasSecondaryType(currCall, JCONONPolicyType.JCONON_MACRO_CALL.value())) {
            if (currCall.getType().getId().equals(JCONONFolderType.JCONON_COMPETITION.value()))
                return null;
            currCall = currCall.getFolderParent();
        }
        return currCall.equals(call) ? null : currCall;
    }

    @Deprecated
    public long findTotalNumApplication(Session cmisSession, Folder call) {
        Criteria criteria = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
        criteria.addColumn(PropertyIds.OBJECT_ID);
        criteria.addColumn(PropertyIds.NAME);
        criteria.add(Restrictions.inTree(call.getId()));
        ItemIterable<QueryResult> iterable = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        return iterable.getTotalNumItems();
    }

    public long getTotalNumApplication(Session cmisSession, Folder call, String userId, String statoDomanda) {
        Folder macroCall = getMacroCall(cmisSession, call);
        if (macroCall != null) {
            Criteria criteria = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
            criteria.addColumn(PropertyIds.OBJECT_ID);
            criteria.addColumn(PropertyIds.NAME);
            criteria.add(Restrictions.inTree(macroCall.getId()));
            if (userId != null)
                criteria.add(Restrictions.eq(JCONONPropertyIds.APPLICATION_USER.value(), userId));
            if (statoDomanda != null)
                criteria.add(Restrictions.eq(JCONONPropertyIds.APPLICATION_STATO_DOMANDA.value(), statoDomanda));
            ItemIterable<QueryResult> iterable = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
            return iterable.getTotalNumItems();
        } else {
            return 0;
        }
    }

    public List<String> findDocumentFinal(Session cmisSession, BindingSession bindingSession, String objectIdBando, JCONONDocumentType documentType) {
    	List<String> result = new ArrayList<String>();
        Criteria criteriaDomande = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
        criteriaDomande.add(Restrictions.inTree(objectIdBando));
        criteriaDomande.add(Restrictions.eq(JCONONPropertyIds.APPLICATION_STATO_DOMANDA.value(), DOMANDA_CONFERMATA));
        criteriaDomande.add(Restrictions.isNull(JCONONPropertyIds.APPLICATION_ESCLUSIONE_RINUNCIA.value()));
        ItemIterable<QueryResult> domande = criteriaDomande.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        for (QueryResult queryResultDomande : domande.getPage(Integer.MAX_VALUE)) {
            String applicationAttach = findAttachmentId(cmisSession, (String) queryResultDomande.getPropertyValueById(PropertyIds.OBJECT_ID),
            		documentType, true);
            if (applicationAttach != null) {
            	result.add(applicationAttach);
            }
        }
        return result;
    }
    public String findAttachmentId(Session cmisSession, String source, JCONONDocumentType documentType, boolean fullNodeRef) {
        Criteria criteria = CriteriaFactory.createCriteria(documentType.queryName());
        criteria.addColumn(PropertyIds.OBJECT_ID);
        if (fullNodeRef)
        	criteria.addColumn(CoolPropertyIds.ALFCMIS_NODEREF.value());
        criteria.addColumn(PropertyIds.NAME);
        criteria.add(Restrictions.inFolder(source));
        ItemIterable<QueryResult> iterable = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        for (QueryResult queryResult : iterable) {
            return queryResult.getPropertyValueById(fullNodeRef ? CoolPropertyIds.ALFCMIS_NODEREF.value() : PropertyIds.OBJECT_ID);
        }
        return null;    	
    }    
    public String findAttachmentId(Session cmisSession, String source, JCONONDocumentType documentType) {
    	return findAttachmentId(cmisSession, source, documentType, false);
    }

    public String findAttachmentName(Session cmisSession, String source, String name) {
        Criteria criteria = CriteriaFactory.createCriteria(BaseTypeId.CMIS_DOCUMENT.value());
        criteria.addColumn(PropertyIds.OBJECT_ID);
        criteria.addColumn(PropertyIds.NAME);
        criteria.add(Restrictions.inFolder(source));
        criteria.add(Restrictions.eq(PropertyIds.NAME, name));
        ItemIterable<QueryResult> iterable = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        for (QueryResult queryResult : iterable) {
            return queryResult.getPropertyValueById(PropertyIds.OBJECT_ID);
        }
        return null;
    }

    public void sollecitaApplication(Session cmisSession) {

        Criteria criteria = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_CALL.queryName());
        ItemIterable<QueryResult> bandi = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());

        for (QueryResult queryResult : bandi.getPage(Integer.MAX_VALUE)) {
            BigInteger numGiorniSollecito = queryResult.getPropertyValueById(
                    JCONONPropertyIds.CALL_NUM_GIORNI_MAIL_SOLLECITO.value());
            if (numGiorniSollecito == null)
                numGiorniSollecito = new BigInteger("8");
            Calendar dataLimite = Calendar.getInstance();
            dataLimite.add(Calendar.DAY_OF_YEAR, numGiorniSollecito.intValue());

            Calendar dataFineDomande = queryResult.getPropertyValueById(JCONONPropertyIds.CALL_DATA_FINE_INVIO_DOMANDE.value());
            if (dataFineDomande == null)
            	continue;
            if (dataFineDomande.before(dataLimite) && dataFineDomande.after(Calendar.getInstance())) {
                Criteria criteriaDomande = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
                criteriaDomande.add(Restrictions.inFolder((String) queryResult.getPropertyValueById(PropertyIds.OBJECT_ID)));
                criteriaDomande.add(Restrictions.eq(JCONONPropertyIds.APPLICATION_STATO_DOMANDA.value(), DOMANDA_PROVVISORIA));
                ItemIterable<QueryResult> domande = criteriaDomande.executeQuery(cmisSession, false, cmisSession.getDefaultContext());

                for (QueryResult queryResultDomande : domande.getPage(Integer.MAX_VALUE)) {
                    EmailMessage message = new EmailMessage();
                    List<String> emailList = new ArrayList<String>();
                    try {
                        CMISUser user = userService.loadUserForConfirm((String) queryResultDomande.getPropertyValueById(JCONONPropertyIds.APPLICATION_USER.value()));
                        if (user != null && user.getEmail() != null) {
                            emailList.add(user.getEmail());

                            message.setRecipients(emailList);
                            message.setSubject("[concorsi] " + i18NService.getLabel("subject-reminder-domanda", Locale.ITALIAN,
                                    queryResult.getPropertyValueById(JCONONPropertyIds.CALL_CODICE.value()),
                                    removeHtmlFromString((String) queryResult.getPropertyValueById(JCONONPropertyIds.CALL_DESCRIZIONE.value()))));
                            Map<String, Object> templateModel = new HashMap<String, Object>();
                            templateModel.put("call", queryResult);
                            templateModel.put("folder", queryResultDomande);
                            templateModel.put("message", context.getBean("messageMethod", Locale.ITALIAN));                            
                            String body = Util.processTemplate(templateModel, "/pages/call/call.reminder.application.html.ftl");
                            message.setBody(body);
                            mailService.send(message);
                            LOGGER.info("Spedita mail a " + user.getEmail() + " per il bando " + message.getSubject());
                        }
                    } catch (Exception e) {
                        LOGGER.error("Cannot send email for scheduler reminder application for call", e);
                    }
                }
            }
        }
    }

    private String removeHtmlFromString(String stringWithHtml) {
        if (stringWithHtml == null) return null;
        stringWithHtml = stringWithHtml.replace("&rsquo;", "'");
        stringWithHtml = stringWithHtml.replace("&amp;", "'");
        stringWithHtml = stringWithHtml.replaceAll("\\<.*?>", "");
        stringWithHtml = stringWithHtml.replaceAll("\\&.*?\\;", "");
        stringWithHtml = stringWithHtml.replace("\r\n", " ");
        stringWithHtml = stringWithHtml.replace("\r", " ");
        stringWithHtml = stringWithHtml.replace("\n", " ");
        return stringWithHtml;
    }

    /**
     * Metodi per la cache delle Abilitazioni
     */
    @Override
    public String name() {
        return "enableTypeCalls";
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public void clear(String username) {
        cache.invalidate(username);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        cache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, versionService.isProduction() ? TimeUnit.HOURS : TimeUnit.MINUTES)
                .build();
        cacheService.register(this);
    }

    @Override
    public String get(final CMISUser user, BindingSession session) {
        try {
            return cache.get(user.getId(), new Callable<String>() {
                @Override
                public String call() throws Exception {
                    ItemIterable<ObjectType> objectTypes = cmisService.createAdminSession().
                            getTypeChildren(JCONONFolderType.JCONON_CALL.value(), false);
                    JSONArray json = new JSONArray();

                    for (ObjectType objectType : objectTypes) {

                        boolean isAuthorized = permission.isAuthorized(objectType.getId(), "PUT",
                                user.getId(), GroupsUtils.getGroups(user));
                        LOGGER.debug(objectType.getId() + " "
                                + (isAuthorized ? "authorized" : "unauthorized"));
                        if (isAuthorized) {
                            try {
                                JSONObject jsonObj = new JSONObject();
                                jsonObj.put("id", objectType.getId());
                                jsonObj.put("title", objectType.getDisplayName());
                                json.put(jsonObj);
                            } catch (JSONException e) {
                                LOGGER.error("errore nel parsing del JSON", e);
                            }
                        }
                    }
                    return json.toString();
                }
            });
        } catch (ExecutionException e) {
            LOGGER.error("Cannot load enableTypeCalls cache for user:" + user.getId(), e);
            throw new ClientMessageException(e.getMessage());
        }
    }

    public String getCallGroupCommissioneName(Folder call) {
        String groupName = "COMMISSIONE_".concat((String) call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value()));
        if (groupName.length() > 100)
            groupName = groupName.substring(0, 100);
        return groupName;
    }

    public String getCallGroupRdPName(Folder call) {
        String groupName = "RDP_".concat((String) call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value()));
        if (groupName.length() > 100)
            groupName = groupName.substring(0, 100);
        return groupName;
    }

    public List<String> getGroupsCallToApplication(Folder call) {
        List<String> results = new ArrayList<String>();
        results.add("GROUP_" + call.getProperty(JCONONPropertyIds.CALL_COMMISSIONE.value()).getValueAsString());
        Folder macroCall = getMacroCall(cmisService.createAdminSession(), call);
        if (macroCall != null) {
            results.add("GROUP_" + macroCall.getProperty(JCONONPropertyIds.CALL_COMMISSIONE.value()).getValueAsString());
        }
        return results;
    }

    public void isBandoInCorso(Folder call, CMISUser loginUser) {
        Calendar dtPubblBando = (Calendar) call.getPropertyValue(JCONONPropertyIds.CALL_DATA_INIZIO_INVIO_DOMANDE.value());
        Calendar dtScadenzaBando = (Calendar) call.getPropertyValue(JCONONPropertyIds.CALL_DATA_FINE_INVIO_DOMANDE.value());
        Calendar currDate = new GregorianCalendar();
        Boolean isBandoInCorso = Boolean.FALSE;
        if (dtScadenzaBando == null && dtPubblBando != null && dtPubblBando.before(currDate))
            isBandoInCorso = Boolean.TRUE;
        if (dtScadenzaBando != null && dtPubblBando != null && dtPubblBando.before(currDate) && dtScadenzaBando.after(currDate))
            isBandoInCorso = Boolean.TRUE;
        if (!isBandoInCorso && !loginUser.isAdmin())
            throw new ClientMessageException("message.error.bando.scaduto");
    }

    private void moveCall(Session cmisSession, GregorianCalendar dataInizioInvioDomande, Folder call) {
        String year = String.valueOf(dataInizioInvioDomande.get(Calendar.YEAR));
        String month = String.valueOf(dataInizioInvioDomande.get(Calendar.MONTH) + 1);
        Folder folderYear = folderService.createFolderFromPath(cmisSession, competitionFolderService.getCompetition().getPath(), year);
        Folder folderMonth = folderService.createFolderFromPath(cmisSession, folderYear.getPath(), month);
        Folder callFolder = ((Folder) cmisSession.getObject(call.getId()));

        Criteria criteria = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
        criteria.add(Restrictions.inTree(call.getId()));
        ItemIterable<QueryResult> applications = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        if (applications.getTotalNumItems() == 0 && !folderMonth.getId().equals(callFolder.getParentId())) {
            callFolder.move(new ObjectIdImpl(callFolder.getParentId()), folderMonth);
        }    	
    }
    
    public boolean isAlphaNumeric(String s){
        String pattern= "^[a-zA-Z0-9 .-]*$";
            if(s.matches(pattern)){
                return true;
            }
            return false;   
    }
    
    public Folder save(Session cmisSession, BindingSession bindingSession, String contextURL, Locale locale, String userId,
                       Map<String, Object> properties, Map<String, Object> aspectProperties) {
        Folder call;
        properties.putAll(aspectProperties);
        String codiceBando = (String)properties.get(JCONONPropertyIds.CALL_CODICE.value());
        /**
         * Verifico inizialmente se sto in creazione del Bando
         */
        if (codiceBando == null)
            throw new ClientMessageException("message.error.required.codice");

        if (!isAlphaNumeric(codiceBando)) {
            throw new ClientMessageException("message.error.codice.not.valid");			
		}
        String name = BANDO_NAME.concat(codiceBando);
        if (properties.get(JCONONPropertyIds.CALL_SEDE.value()) != null)
            name = name.concat(" - ").
                    concat(properties.get(JCONONPropertyIds.CALL_SEDE.value()).toString());
        properties.put(PropertyIds.NAME, folderService.integrityChecker(name));
        GregorianCalendar dataInizioInvioDomande = (GregorianCalendar) properties.get(JCONONPropertyIds.CALL_DATA_INIZIO_INVIO_DOMANDE.value());
        Map<String, Object> otherProperties = new HashMap<String, Object>();
        if (properties.get(PropertyIds.OBJECT_ID) == null) {
            if (properties.get(PropertyIds.PARENT_ID) == null)
                properties.put(PropertyIds.PARENT_ID, competitionFolderService.getCompetition().getId());
            call = (Folder) cmisSession.getObject(
                    cmisSession.createFolder(properties, new ObjectIdImpl((String) properties.get(PropertyIds.PARENT_ID))));
            aclService.setInheritedPermission(bindingSession, call.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString(), false);
            if (dataInizioInvioDomande != null && properties.get(PropertyIds.PARENT_ID) == null) {
            	moveCall(cmisSession, dataInizioInvioDomande, call);
            }
        } else {
            call = (Folder) cmisSession.getObject((String) properties.get(PropertyIds.OBJECT_ID));
            call.updateProperties(properties, true);
            if (!call.getParentId().equals(properties.get(PropertyIds.PARENT_ID)) && properties.get(PropertyIds.PARENT_ID) != null)
                call.move(call.getFolderParent(), new ObjectIdImpl((String) properties.get(PropertyIds.PARENT_ID)));

        }
        if (cmisSession.getObject(call.getParentId()).getType().getId().equals(call.getType().getId()))
            otherProperties.put(JCONONPropertyIds.CALL_HAS_MACRO_CALL.value(), true);
        else
            otherProperties.put(JCONONPropertyIds.CALL_HAS_MACRO_CALL.value(), false);
        List<Object> secondaryTypes = call.getProperty(PropertyIds.SECONDARY_OBJECT_TYPE_IDS).getValues();
        if (!typeService.hasSecondaryType(call, JCONONPolicyType.JCONON_MACRO_CALL.value())) {
            secondaryTypes.add(JCONONPolicyType.JCONON_CALL_SUBMIT_APPLICATION.value());
        } else {
            secondaryTypes.remove(JCONONPolicyType.JCONON_CALL_SUBMIT_APPLICATION.value());
        }
        otherProperties.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryTypes);
        call.updateProperties(otherProperties);
        creaGruppoRdP(call, userId);
        creaGruppoCommissione(call, userId);
        //reset cache
        cacheService.clearCacheWithName("nodeParentsCache");
        return call;
    }

    public void delete(Session cmisSession, String contextURL, String objectId,
                       String objectTypeId) {
        Criteria criteria = CriteriaFactory.createCriteria(JCONONFolderType.JCONON_APPLICATION.queryName());
        criteria.add(Restrictions.ne(JCONONPropertyIds.APPLICATION_STATO_DOMANDA.value(), "I"));
        criteria.add(Restrictions.inTree(objectId));
        ItemIterable<QueryResult> applications = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        if (applications.getTotalNumItems() > 0)
            throw new ClientMessageException("message.error.call.cannot.delete");
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Try to delete :" + objectId);
        Folder call = (Folder) cmisSession.getObject(objectId);
        call.deleteTree(true, UnfileObject.DELETE, true);
    }

    private boolean isCallAttachmentPresent(Session cmisSession, Folder source, JCONONDocumentType documentType) {
        Criteria criteria = CriteriaFactory.createCriteria(documentType.queryName());
        criteria.add(Restrictions.inFolder(source.getId()));
        if (criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext()).getTotalNumItems() == 0)
            return false;
        return true;
    }

    private void addACL(String principal, ACLType aclType, String nodeRef) {
        Map<String, ACLType> aces = new HashMap<String, ACLType>();
        aces.put(principal, aclType);
        aclService.addAcl(cmisService.getAdminSession(), nodeRef, aces);    	
    }
    
    private void addContibutorCommission(String groupName, String nodeRefCall) {
    	addACL("GROUP_" + groupName, ACLType.Contributor, nodeRefCall);
    }

    private void addCoordinatorRdp(String groupName, String nodeRefCall) {
    	addACL("GROUP_" + groupName, ACLType.Coordinator, nodeRefCall);
    }

    private void creaGruppoRdP(final Folder call, String userId) {
    	if (call.getPropertyValue(JCONONPropertyIds.CALL_RDP.value()) != null)
    		return;
        //Creazione del gruppo per i Responsabili del Procedimento
        final String groupRdPName = getCallGroupRdPName(call);
        try {
            String link = cmisService.getBaseURL().concat("service/cnr/groups/group");
            UrlBuilder url = new UrlBuilder(link);
            Response response = CmisBindingsHelper.getHttpInvoker(
            		cmisService.getAdminSession()).invokePOST(url, MimeTypes.JSON.mimetype(),
                    new Output() {
                        @Override
                        public void write(OutputStream out) throws Exception {
                        	String groupJson = "{";
                        	groupJson = groupJson.concat("\"parent_group_name\":\"" + GROUP_RDP_CONCORSO + "\",");
                        	groupJson = groupJson.concat("\"group_name\":\"" + groupRdPName + "\",");
                        	groupJson = groupJson.concat("\"display_name\":\"" + "RESPONSABILI BANDO [".concat((String) call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value())) + "]\",");
                        	groupJson = groupJson.concat("\"zones\":[\"AUTH.ALF\",\"APP.DEFAULT\"]");                        	
                        	groupJson = groupJson.concat("}");
                        	out.write(groupJson.getBytes());
                        }
                    }, cmisService.getAdminSession());
            JSONObject jsonObject = new JSONObject(StringUtil.convertStreamToString(response.getStream()));            
            String nodeRefRdP = jsonObject.optString("nodeRef");
            if (nodeRefRdP == "")
            	return;
            /**
             * Aggiorno il bando con il NodeRef del gruppo commissione
             */
            Map<String, Object> propertiesRdP = new HashMap<String, Object>();
            propertiesRdP.put(JCONONPropertyIds.CALL_RDP.value(), groupRdPName);            
            call.updateProperties(propertiesRdP, true);
            /**
             * Il Gruppo dei responsabili del procedimento deve avere il controllo completo sul bando
             */
            addCoordinatorRdp(groupRdPName, 
            		call.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString());
            Map<String, ACLType> acesGroup = new HashMap<String, ACLType>();
            acesGroup.put(userId, ACLType.FullControl);
            acesGroup.put(GROUP_CONCORSI, ACLType.FullControl);
            aclService.addAcl(cmisService.getAdminSession(), nodeRefRdP, acesGroup);
        } catch (Exception e) {
            LOGGER.error("ACL error", e);
        }    	
    }
    
    private void creaGruppoCommissione(final Folder call, String userId) {
    	if (call.getPropertyValue(JCONONPropertyIds.CALL_COMMISSIONE.value()) != null)
    		return;    	
        //Creazione del gruppo per la commissione di Concorso
        final String groupRdPName = getCallGroupRdPName(call);
        final String groupCommissioneName = getCallGroupCommissioneName(call);
        try {
            String link = cmisService.getBaseURL().concat("service/cnr/groups/group");
            UrlBuilder url = new UrlBuilder(link);
            Response response = CmisBindingsHelper.getHttpInvoker(
            		cmisService.getAdminSession()).invokePOST(url, MimeTypes.JSON.mimetype(),
                    new Output() {
                        @Override
                        public void write(OutputStream out) throws Exception {
                        	String groupJson = "{";
                        	groupJson = groupJson.concat("\"parent_group_name\":\"" + GROUP_COMMISSIONI_CONCORSO + "\",");
                        	groupJson = groupJson.concat("\"group_name\":\"" + groupCommissioneName + "\",");
                        	groupJson = groupJson.concat("\"display_name\":\"" + "COMMISSIONE BANDO [".concat((String) call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value())) + "]\",");
                        	groupJson = groupJson.concat("\"zones\":[\"AUTH.ALF\",\"APP.DEFAULT\"]");
                        	groupJson = groupJson.concat("}");
                        	out.write(groupJson.getBytes());
                        }
                    }, cmisService.getAdminSession());
            JSONObject jsonObject = new JSONObject(StringUtil.convertStreamToString(response.getStream()));            
            String nodeRef = jsonObject.optString("nodeRef");
            if (nodeRef == "")
            	return;
            
            /**
             * Aggiorno il bando con il NodeRef del gruppo commissione
             */
            Map<String, Object> propertiesCommissione = new HashMap<String, Object>();
            propertiesCommissione.put(JCONONPropertyIds.CALL_COMMISSIONE.value(), groupCommissioneName);            
            call.updateProperties(propertiesCommissione, true);
            /**
             * Il Gruppo della Commissione deve avere il ruolo di contributor sul bando
             */
            addContibutorCommission(groupCommissioneName, call.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString());
            Map<String, ACLType> acesGroup = new HashMap<String, ACLType>();
            acesGroup.put(userId, ACLType.FullControl);
            acesGroup.put(GROUP_CONCORSI, ACLType.FullControl);
            acesGroup.put("GROUP_" + groupRdPName, ACLType.FullControl);
            aclService.addAcl(cmisService.getAdminSession(), nodeRef, acesGroup);
        } catch (Exception e) {
            LOGGER.error("ACL error", e);
        }    	
    }
    public Folder publish(Session cmisSession, BindingSession currentBindingSession, String userId, String objectId, boolean publish,
                          String contextURL, Locale locale) {
        final Folder call = (Folder) cmisSession.getObject(objectId);
        Map<String, ACLType> aces = new HashMap<String, ACLType>();
        aces.put(GROUP_CONCORSI, ACLType.Coordinator);
        aces.put(GROUP_EVERYONE, ACLType.Consumer);

        if (JCONONPolicyType.isIncomplete(call))
            throw new ClientMessageException("message.error.call.incomplete");

        if (call.getType().getId().equalsIgnoreCase(JCONONFolderType.JCONON_CALL_MOBILITY.value()) ||
                call.getType().getId().equalsIgnoreCase(JCONONFolderType.JCONON_CALL_MOBILITY_OPEN.value())) {
            if (!isCallAttachmentPresent(cmisSession, call, JCONONDocumentType.JCONON_ATTACHMENT_CALL_MOBILITY))
                throw new ClientMessageException("message.error.call.mobility.incomplete.attachment");
        } else {
            if (!isCallAttachmentPresent(cmisSession, call, JCONONDocumentType.JCONON_ATTACHMENT_CALL_IT))
                throw new ClientMessageException("message.error.call.incomplete.attachment");
        }
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(JCONONPropertyIds.CALL_PUBBLICATO.value(), publish);        
        if (publish) {
        	if (call.getPropertyValue(JCONONPropertyIds.CALL_ID_CATEGORIA_TECNICO_HELPDESK.value()) == null) {
                Integer idCategoriaCallType = helpdeskService.getCategoriaMaster(call.getType().getId());       	
            	Integer idCategoriaHelpDESK = helpdeskService.createCategoria(idCategoriaCallType, 
            			"BANDO " + call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value()), 
            			"BANDO " + call.getPropertyValue(JCONONPropertyIds.CALL_CODICE.value()));
            	Integer idCategoriaTecnicoHelpDESK = helpdeskService.createCategoria(idCategoriaHelpDESK, "Problema Tecnico", "Problema Tecnico");
            	Integer idCategoriaNormativaHelpDESK = helpdeskService.createCategoria(idCategoriaHelpDESK, "Problema Normativo", "Problema Normativo");
                properties.put(JCONONPropertyIds.CALL_ID_CATEGORIA_TECNICO_HELPDESK.value(), idCategoriaTecnicoHelpDESK);        	
                properties.put(JCONONPropertyIds.CALL_ID_CATEGORIA_NORMATIVA_HELPDESK.value(), idCategoriaNormativaHelpDESK);        	        		
        	}
            aclService.addAcl(currentBindingSession, call.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString(), aces);
        } else {
            aclService.removeAcl(currentBindingSession, call.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString(), aces);
        }
        call.updateProperties(properties, true);
        return call;
    }

    @SuppressWarnings("unchecked")
    public void crateChildCall(Session cmisSession, BindingSession currentBindingSession, String userId,
                               Map<String, Object> extractFormParams, String contextURL,
                               Locale locale) {
        Folder parent = (Folder) cmisSession.getObject(String.valueOf(extractFormParams.get(PropertyIds.PARENT_ID)));
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.putAll(extractFormParams);
        for (Property<?> property : parent.getProperties()) {
            if (!extractFormParams.containsKey(property.getId()) &&
                    !property.getDefinition().getUpdatability().equals(Updatability.READONLY)) {
                LOGGER.debug("Add property " + property.getId() + " for create child.");
                properties.put(property.getId(), property.getValue());
            }
        }
        String name = BANDO_NAME.concat(properties.get(JCONONPropertyIds.CALL_CODICE.value()).toString());
        if (properties.get(JCONONPropertyIds.CALL_SEDE.value()) != null)
            name = name.concat(" - ").
                    concat(properties.get(JCONONPropertyIds.CALL_SEDE.value()).toString());
        properties.put(PropertyIds.NAME, folderService.integrityChecker(name));

        for (PropertyDefinition<?> property : cmisSession.getTypeDefinition(JCONONPolicyType.JCONON_MACRO_CALL.value()).
                getPropertyDefinitions().values()) {
            if (!property.isInherited())
                properties.remove(property.getId());
        }
        properties.put(JCONONPropertyIds.CALL_HAS_MACRO_CALL.value(), true);
        List<String> secondaryTypes = ((List<String>) properties.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
        secondaryTypes.add(JCONONPolicyType.JCONON_CALL_SUBMIT_APPLICATION.value());
        properties.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryTypes);
        properties.put(JCONONPropertyIds.CALL_PUBBLICATO.value(), false);

        Folder child = parent.createFolder(properties);
        aclService.setInheritedPermission(currentBindingSession,
                child.getProperty(CoolPropertyIds.ALFCMIS_NODEREF.value()).getValueAsString(),
                false);

        Criteria criteria = CriteriaFactory.createCriteria(JCONONDocumentType.JCONON_ATTACHMENT_CALL_ABSTRACT.queryName());
        criteria.addColumn(PropertyIds.OBJECT_ID);
        criteria.add(Restrictions.inFolder(child.getParentId()));
        ItemIterable<QueryResult> attachments = criteria.executeQuery(cmisSession, false, cmisSession.getDefaultContext());
        for (QueryResult attachment : attachments) {
            Document attachmentFile = (Document) cmisSession.getObject(
                    String.valueOf(attachment.getPropertyValueById(PropertyIds.OBJECT_ID)));
            Map<String, Object> childProperties = new HashMap<String, Object>();
            for (Property<?> property : attachmentFile.getProperties()) {
                if (!property.getDefinition().getUpdatability().equals(Updatability.READONLY))
                    childProperties.put(property.getId(), property.getValue());
            }
            Map<String, Object> initialProperties = new HashMap<String, Object>();
            initialProperties.put(PropertyIds.NAME, childProperties.get(PropertyIds.NAME));
            initialProperties.put(PropertyIds.OBJECT_TYPE_ID, childProperties.get(PropertyIds.OBJECT_TYPE_ID));
            Document newDocument = (Document)
                    child.createDocument(initialProperties, attachmentFile.getContentStream(), VersioningState.MAJOR);
            newDocument.updateProperties(childProperties);
        }
    }

    public Properties getDynamicLabels(ObjectId objectId, Session cmisSession) {
		LOGGER.debug("loading dynamic labels for " + objectId);
        Properties labels = callRepository.getLabelsForObjectId(objectId.getId(), cmisSession);
		return labels;
	}

    public JsonObject getJSONLabels(ObjectId objectId, Session cmisSession) {
		LOGGER.debug("loading json labels for " + objectId);
		String labelId = findAttachmentName(cmisSession, objectId.getId(), CallRepository.LABELS_JSON);
		if (labelId != null)
			return new JsonParser().parse(new InputStreamReader(cmisSession.getContentStream(new ObjectIdImpl(labelId)).getStream())).getAsJsonObject();
		return null;
	}

    public JsonObject storeDynamicLabels(ObjectId objectId, Session cmisSession, String key, String oldLabel, String newLabel, boolean delete) {
		LOGGER.debug("store dynamic labels for " + objectId);
		String labelId = callRepository.findAttachmentLabels(cmisSession, objectId.getId());
		ContentStreamImpl contentStream = new ContentStreamImpl();
		JsonObject labels = new JsonObject();
		if (labelId != null) {				
			contentStream = (ContentStreamImpl) cmisSession.getContentStream(new ObjectIdImpl(labelId));
			labels = new JsonParser().parse(new InputStreamReader(cmisSession.getContentStream(new ObjectIdImpl(labelId)).getStream())).getAsJsonObject();
		}
		if (delete) {
			labels.remove(key);
		} else {
			if (labels.has(key)) {
				JsonObject value = labels.get(key).getAsJsonObject();
				value.addProperty("newLabel", newLabel);
			} else {
				JsonObject value = new JsonObject();
				value.addProperty("oldLabel", oldLabel);
				value.addProperty("newLabel", newLabel);			
				labels.add(key, value);				
			}
		}
		contentStream.setStream(new ByteArrayInputStream(labels.toString().getBytes()));
		if (labelId != null) {
			((Document)cmisSession.getObject(labelId)).setContentStream(contentStream, true);				
		} else {
			contentStream.setMimeType("application/json");
			Map<String, Object> properties = new HashMap<String, Object>();
			properties.put(PropertyIds.NAME, CallRepository.LABELS_JSON);
			properties.put(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());
			cmisSession.createDocument(properties,  objectId, contentStream, VersioningState.MAJOR);
		}
		callRepository.removeDynamicLabels(objectId.getId());
		return labels;
	}
    
}