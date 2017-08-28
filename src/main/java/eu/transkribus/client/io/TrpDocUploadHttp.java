package eu.transkribus.client.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.xml.bind.JAXBException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.NotImplementedException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.transkribus.client.connection.TrpServerConn;
import eu.transkribus.core.io.util.Md5SumComputer;
import eu.transkribus.core.model.beans.DocumentUploadDescriptor;
import eu.transkribus.core.model.beans.DocumentUploadDescriptor.PageUploadDescriptor;
import eu.transkribus.core.model.beans.TrpDoc;
import eu.transkribus.core.model.beans.TrpDocMetadata;
import eu.transkribus.core.model.beans.TrpPage;
import eu.transkribus.core.model.beans.TrpUpload;
import eu.transkribus.core.model.beans.TrpUpload.UploadType;
import eu.transkribus.core.model.beans.mets.Mets;
import eu.transkribus.core.model.builder.TrpDocUploadBuilder;
import eu.transkribus.core.model.builder.mets.TrpMetsBuilder;
import eu.transkribus.core.util.JaxbUtils;

/**
 * New upload that sends a separate PUT request per page<br/><br/>
 * 
 * TODO-list
 *  <ul>
 *  	<li>do retries if a single page upload fails</li>
 *  	<li>cache upload processes locally (on disk?) in order to allow resuming if application crashed</li>
 *  </ul>
 * 
 */
public class TrpDocUploadHttp extends ASingleDocUpload {
	private static final Logger logger = LoggerFactory.getLogger(TrpDocUploadHttp.class);
	private static final String UPLOAD_XML_NAME = "upload.xml";
	
	/**
	 * If a single page upload fails there will be this many retries before the complete upload fails
	 */
	private static final int NR_OF_RETRIES_ON_FAIL = 3;
	protected final TrpServerConn conn;
	IProgressMonitor monitor = null;
	final int colId;
	final UploadType type;
	final boolean doMd5SumCheck;

	public TrpDocUploadHttp(TrpServerConn conn, final int colId, final TrpDoc entity, final UploadType type,
			final boolean doMd5SumCheck, IProgressMonitor monitor) throws IOException {
		super(entity, monitor);
		if (conn == null) {
			throw new IllegalArgumentException("TrpServer connection is null.");
		}
		this.conn = conn;
		this.colId = colId;
		this.type = type == null ? UploadType.METS : type;
		this.doMd5SumCheck = doMd5SumCheck;
	}

	@Override
	public TrpUpload call() throws Exception {

		/*
		 * TODO check for existence of upload.xml on local folder of doc and try resume.
		 * check if user ID matches before! (might be NAS storage).
		 * (optional: get current state from server via GET)
		 */
		TrpUpload upload = null;
		try {
			if (monitor != null)
				monitor.beginTask("Uploading document at " + doc.getMd().getLocalFolder().getAbsolutePath(),
						100);

			if (doMd5SumCheck) {
				updateStatus("Computing checksums...");
				Md5SumComputer md5Comp = new Md5SumComputer();
				md5Comp.addObserver(passthroughObserver);
				doc = md5Comp.computeAndSetMd5Sums(doc);
			}

			updateStatus("Initiating upload...");
			
			final int uploadId;
			switch (type) {
			case METS:
				Mets mets = TrpMetsBuilder.buildMets(doc, true, false, true, null);
				upload = conn.createNewUpload(colId, mets);
				break;
			case JSON:
				DocumentUploadDescriptor struct = TrpDocUploadBuilder.build(doc);
				upload = conn.createNewUpload(colId, struct);
				break;
			case NoStructure:
				throw new NotImplementedException();
			default:
				throw new IllegalArgumentException("type is null.");	
			}
			uploadId = upload.getUploadId();
			
			// put files
			final float percentPerPage = 100f / doc.getNPages();			
			for(TrpPage p : doc.getPages()) {
				File img = FileUtils.toFile(p.getUrl());
				File xml = null;
				if(!p.getTranscripts().isEmpty()) {
					xml = FileUtils.toFile(p.getCurrentTranscript().getUrl());
				}
				
				//retry loop if putPage fails
				int tries = 0;
				Exception ex;
				do {
					try {
						upload = conn.putPage(uploadId, img, xml);
						ex = null;
					} catch(Exception e) {
						logger.error("Could not post image: " + img.getName(), e);
						ex = e;
					}
				} while (tries++ <= NR_OF_RETRIES_ON_FAIL && ex != null);
				if(ex != null) {
					throw ex;
				}
				logger.debug("Page nr.: " + p.getPageNr() + " | percentPerPage = " + percentPerPage);
				final int percent = new Double(percentPerPage * p.getPageNr()).intValue();
				updateStatus(Integer.valueOf(percent));
			}
			
			// the last PUT request's response includes the job ID
			logger.info("Ingest-job ID = " + upload.getJobId());
			
//			updateStatus("Upload done. Waiting for server...");
//			while(upload.getJobId() == null) {
//				Thread.sleep(3000);
//				//poll service for jobId
//				upload = conn.getUploadStatus(uploadId);
//				if(upload.isUploadComplete()) {
//					updateStatus("Server is starting ingest job...");
//				}
//			}
			
		} catch (OperationCanceledException oce) {
			logger.info("Upload canceled: " + oce.getMessage());
			storeUploadXmlOnDisk(upload, doc.getMd().getLocalFolder());
		} catch (Exception e) {
			logger.error("Upload failed!", e);
			storeUploadXmlOnDisk(upload, doc.getMd().getLocalFolder());
		}

		return upload;
	}

	private void storeUploadXmlOnDisk(TrpUpload upload, File localFolder) {
		if(upload == null) {
			return;
		}
		File xml = new File(localFolder.getAbsolutePath() + File.separator + UPLOAD_XML_NAME);
		try {
			JaxbUtils.marshalToFile(upload, xml, TrpDocMetadata.class, PageUploadDescriptor.class);
		} catch (FileNotFoundException | JAXBException e) {
			logger.error("Could not store upload.xml!", e);
		}
	}
}