package com.dotcms.publisher.pusher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dotcms.enterprise.LicenseUtil;
import com.dotcms.enterprise.publishing.remote.bundler.*;
import com.dotcms.publisher.bundle.bean.Bundle;
import com.dotcms.publisher.business.DotPublisherException;
import com.dotcms.publisher.business.EndpointDetail;
import com.dotcms.publisher.business.PublishAuditAPI;
import com.dotcms.publisher.business.PublishAuditHistory;
import com.dotcms.publisher.business.PublishAuditStatus;
import com.dotcms.publisher.business.PublishQueueElement;
import com.dotcms.publisher.business.PublisherQueueJob;
import com.dotcms.publisher.endpoint.bean.PublishingEndPoint;
import com.dotcms.publisher.environment.bean.Environment;
import com.dotcms.publisher.util.PusheableAsset;
import com.dotcms.publishing.BundlerUtil;
import com.dotcms.publishing.DotPublishingException;
import com.dotcms.publishing.IBundler;
import com.dotcms.publishing.PublishStatus;
import com.dotcms.publishing.Publisher;
import com.dotcms.publishing.PublisherConfig;
import com.dotcms.repackage.javax.ws.rs.client.Client;
import com.dotcms.repackage.javax.ws.rs.client.Entity;
import com.dotcms.repackage.javax.ws.rs.client.WebTarget;
import com.dotcms.repackage.javax.ws.rs.core.MediaType;
import com.dotcms.repackage.javax.ws.rs.core.Response;
import com.dotcms.repackage.org.apache.commons.httpclient.HttpStatus;
import com.dotcms.repackage.org.apache.commons.io.FileUtils;
import com.dotcms.repackage.org.glassfish.jersey.media.multipart.FormDataMultiPart;
import com.dotcms.repackage.org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import com.dotcms.rest.RestClientBuilder;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.cms.factories.PublicEncryptionFactory;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.PushPublishLogger;
import com.dotmarketing.util.UtilMethods;

/**
 * This is the main content publishing class in the Push Publishing process.
 * This class defines the list of bundlers that will take the pusheable objects
 * selected by the user, and sends the zipped bundle to the destination server.
 * The purpose of the bundlers ({@link IBundler} objects) is to provide a way to
 * say how to write out the different parts and objects of the bundle.
 * <p>
 * This publisher is also aware of the publishing status of the bundle in the
 * destination server(s). This means that it updates the local status of the
 * bundle so users will know if the bundle was successfully deployed or if
 * something failed during the process.
 * 
 * @author Alberto
 * @version 1.0
 * @since Oct 12, 2012
 *
 */
public class PushPublisher extends Publisher {

    private PublishAuditAPI pubAuditAPI = PublishAuditAPI.getInstance();
    private static final String PROTOCOL_HTTP = "http";
    private static final String PROTOCOL_HTTPS = "https";

    @Override
    public PublisherConfig init ( PublisherConfig config ) throws DotPublishingException {
        if ( LicenseUtil.getLevel() < 300 ) {
            throw new RuntimeException( "need an enterprise pro license to run this bundler" );
        }
        this.config = super.init( config );
        return this.config;
    }

    /**
     * Final step on the Publishing of a Bundle. This method will generate the Bundle file compressing all the information<br/>
     * generated by the Bundlers into a tar.gz file what will live on the assets directory, after the Bundle is created<br/>
     * this process will try to send the Bundle to a list of previously selected Environments.
     *
     * @param status Current status of the Publishing process
     * @return This bundle configuration ({@link PublisherConfig})
     * @throws DotPublishingException
     * @see com.dotcms.publisher.environment.bean.Environment
     */
    @Override
    public PublisherConfig process ( final PublishStatus status ) throws DotPublishingException {
		if(LicenseUtil.getLevel()<300) {
	        throw new RuntimeException("need an enterprise pro license to run this bundler");
        }

	    PublishAuditHistory currentStatusHistory = null;
		try {
			//Compressing bundle
			File bundleRoot = BundlerUtil.getBundleRoot(config);

			ArrayList<File> list = new ArrayList<File>(1);
			list.add(bundleRoot);
			File bundle = new File(bundleRoot+File.separator+".."+File.separator+config.getId()+".tar.gz");
			PushUtils.compressFiles(list, bundle, bundleRoot.getAbsolutePath());

			List<Environment> environments = APILocator.getEnvironmentAPI().findEnvironmentsByBundleId(config.getId());
			Client client = RestClientBuilder.newClient();

			//Updating audit table
			currentStatusHistory = pubAuditAPI.getPublishAuditStatus(config.getId()).getStatusPojo();
			Map<String, Map<String, EndpointDetail>> endpointsMap = currentStatusHistory.getEndpointsMap();
			// If not empty, don't overwrite publish history already set via the PublisherQueueJob
			boolean isHistoryEmpty = endpointsMap.size() == 0;
			currentStatusHistory.setPublishStart(new Date());
			PushPublishLogger.log(this.getClass(), "Status Update: Sending to all environments");
			pubAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.SENDING_TO_ENDPOINTS, currentStatusHistory);
			//Increment numTries
			currentStatusHistory.addNumTries();
	        int errorCounter = 0;

			for (Environment environment : environments) {
				List<PublishingEndPoint> allEndpoints = APILocator.getPublisherEndPointAPI().findSendingEndPointsByEnvironment(environment.getId());
				List<PublishingEndPoint> endpoints = new ArrayList<PublishingEndPoint>();
				
				//Filter Endpoints list and push only to those that are enabled and are Dynamic (not S3 at the moment)
				for(PublishingEndPoint ep : allEndpoints) {
					if(ep.isEnabled() && getProtocols().contains(ep.getProtocol())) {
						endpoints.add(ep);
					}
				}

				boolean failedEnvironment = false;

				if(!environment.getPushToAll()) {
					Collections.shuffle(endpoints);
					if(!endpoints.isEmpty())
						endpoints = endpoints.subList(0, 1);
				}

				for (PublishingEndPoint endpoint : endpoints) {
					EndpointDetail detail = new EndpointDetail();
	        		try {
	        			FormDataMultiPart form = new FormDataMultiPart();
	        			form.field("AUTH_TOKEN",
	        					retriveKeyString(
	        							PublicEncryptionFactory.decryptString(endpoint.getAuthKey().toString())));

	        			form.field("GROUP_ID", UtilMethods.isSet(endpoint.getGroupId()) ? endpoint.getGroupId() : endpoint.getId());
	        			Bundle b=APILocator.getBundleAPI().getBundleById(config.getId());
	        			form.field("BUNDLE_NAME", b.getName());
	        			form.field("ENDPOINT_ID", endpoint.getId());
	        			form.bodyPart(new FileDataBodyPart("bundle", bundle, MediaType.MULTIPART_FORM_DATA_TYPE));

                        WebTarget webTarget = client.target(endpoint.toURL()+"/api/bundlePublisher/publish");

                        Response response = webTarget.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.entity(form, form.getMediaType()));

	        			if(response.getStatus() == HttpStatus.SC_OK)
	        			{
							PushPublishLogger.log(this.getClass(), "Status Update: Bundle sent");
	        				detail.setStatus(PublishAuditStatus.Status.BUNDLE_SENT_SUCCESSFULLY.getCode());
	        				detail.setInfo("Everything ok");
	        			} else {

	        				if(currentStatusHistory.getNumTries()==PublisherQueueJob.MAX_NUM_TRIES) {
		        				APILocator.getPushedAssetsAPI().deletePushedAssets(config.getId(), environment.getId());
		        			}
	        				detail.setStatus(PublishAuditStatus.Status.FAILED_TO_SENT.getCode());
	        				detail.setInfo(
	        						"Returned "+response.getStatus()+ " status code " +
	        								"for the endpoint "+endpoint.getId()+ "with address "+endpoint.getAddress());
	        				failedEnvironment |= true;
	        			}
	        		} catch(Exception e) {
	        			// if the bundle can't be sent after the total num of tries, delete the pushed assets for this bundle
	        			if(currentStatusHistory.getNumTries()==PublisherQueueJob.MAX_NUM_TRIES) {
	        				APILocator.getPushedAssetsAPI().deletePushedAssets(config.getId(), environment.getId());
	        			}
	        			detail.setStatus(PublishAuditStatus.Status.FAILED_TO_SENT.getCode());
	        			String error = 	"An error occured for the endpoint "+ endpoint.getId() + " with address "+ endpoint.getAddress() + ".  Error: " + e.getMessage();
	        			detail.setInfo(error);
	        			failedEnvironment |= true;

	        			Logger.error(this.getClass(), error);
	        		}
	        		if (isHistoryEmpty || failedEnvironment) {
	        			currentStatusHistory.addOrUpdateEndpoint(environment.getId(), endpoint.getId(), detail);
	        		}
				}

				if(failedEnvironment) {
					errorCounter++;
				}
			}

			if(errorCounter==0) {
				//Updating audit table
				PushPublishLogger.log(this.getClass(), "Status Update: Bundle sent");
				pubAuditAPI.updatePublishAuditStatus(config.getId(),
						PublishAuditStatus.Status.BUNDLE_SENT_SUCCESSFULLY, currentStatusHistory);
			} else {
				if(errorCounter == environments.size()) {
					pubAuditAPI.updatePublishAuditStatus(config.getId(),
							PublishAuditStatus.Status.FAILED_TO_SEND_TO_ALL_GROUPS, currentStatusHistory);
				} else {
					pubAuditAPI.updatePublishAuditStatus(config.getId(),
							PublishAuditStatus.Status.FAILED_TO_SEND_TO_SOME_GROUPS, currentStatusHistory);
				}
			}

			return config;

		} catch (Exception e) {
			//Updating audit table
			try {
				PushPublishLogger.log(this.getClass(), "Status Update: Failed to publish");
				pubAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.FAILED_TO_PUBLISH, currentStatusHistory);
			} catch (DotPublisherException e1) {
				throw new DotPublishingException(e.getMessage());
			}

			Logger.error(this.getClass(), e.getMessage(), e);
			throw new DotPublishingException(e.getMessage());

		}
	}

    /**
     * 
     * @param token
     * @return
     * @throws IOException
     */
	public static String retriveKeyString(String token) throws IOException {
		String key = null;
		if(token.contains(File.separator)) {
			File tokenFile = new File(token);
			if(tokenFile != null && tokenFile.exists())
				key = FileUtils.readFileToString(tokenFile, "UTF-8").trim();
		} else {
			key = token;
		}

		return PublicEncryptionFactory.encryptString(key);
	}

    @SuppressWarnings ("rawtypes")
    @Override
    public List<Class> getBundlers () {
        boolean buildUsers = false;
        boolean buildCategories = false;
        boolean buildOSGIBundle = false;
        boolean buildLanguages = false;
        boolean buildRules = false;
        boolean buildAsset = false;
        List<Class> list = new ArrayList<Class>();
        for ( PublishQueueElement element : config.getAssets() ) {
            if ( element.getType().equals(PusheableAsset.CATEGORY.getType()) ) {
                buildCategories = true;
            } else if ( element.getType().equals(PusheableAsset.OSGI.getType()) ) {
                buildOSGIBundle = true;
            } else if ( element.getType().equals(PusheableAsset.USER.getType()) ) {
                buildUsers = true;
            } else if (element.getType().equals(PusheableAsset.LANGUAGE.getType())) {
            	buildLanguages = true;
            } else if (element.getType().equals(PusheableAsset.RULE.getType())) {
            	buildRules = true;
            } else {
                buildAsset = true;
            }
        }
        if(config.getLuceneQueries().size() > 0){
        	buildAsset = true;
        }
        if ( buildUsers ) {
            list.add( UserBundler.class );
        }
        if ( buildCategories ) {
            list.add( CategoryBundler.class );
        }
        if ( buildOSGIBundle ) {
            list.add( OSGIBundler.class );
        }
        if ( buildAsset ) {
            list.add( DependencyBundler.class );
            list.add( HostBundler.class );
            list.add( ContentBundler.class );
            list.add( FolderBundler.class );
            list.add( TemplateBundler.class );
            list.add( ContainerBundler.class );
            list.add( HTMLPageBundler.class );
            list.add(RuleBundler.class);
            list.add( LinkBundler.class );
            if ( Config.getBooleanProperty("PUSH_PUBLISHING_PUSH_STRUCTURES", false) ) {
                list.add( StructureBundler.class );
                list.add( RelationshipBundler.class );
            }
            list.add( LanguageVariablesBundler.class );
            list.add( WorkflowBundler.class );
            list.add( LanguageBundler.class );
        } else {
			list.add(DependencyBundler.class);
			if (buildLanguages) {
				list.add(LanguageVariablesBundler.class);
				list.add(LanguageBundler.class);
			} else if (buildRules) {
				list.add(HostBundler.class);
				list.add(HTMLPageBundler.class);
				list.add(RuleBundler.class);
			}
        }
        list.add( BundleXMLAsc.class );
        return list;
    }

    @Override
	public Set<String> getProtocols(){
		Set<String> protocols = new HashSet<>();
		protocols.add(this.PROTOCOL_HTTP);
		protocols.add(this.PROTOCOL_HTTPS);
		return  protocols;
	}
}
