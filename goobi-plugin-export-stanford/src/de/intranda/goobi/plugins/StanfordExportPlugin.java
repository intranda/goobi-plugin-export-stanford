package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.StatusType;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.pdfbox.util.PDFMergerUtility;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j
public class StanfordExportPlugin implements IExportPlugin, IPlugin {

    private static final String contentString = "content";
    private static final String typeString = "type";
    private static final String nameString = "name";
    private static final String fileString = "file";
    private static final String labelString = "label";
    private static final String resourceString = "resource";
    private List<String> problems = new ArrayList<>();

    @Override
    public PluginType getType() {
        return PluginType.Export;
    }

    @Override
    public String getTitle() {
        return "intranda_export_stanford";
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
    WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
    TypeNotAllowedForParentException {

        return startExport(process, null);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
    PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
    SwapException, DAOException, TypeNotAllowedForParentException {
        problems = new ArrayList<>();
        XMLConfiguration config = ConfigPlugins.getPluginConfig(getTitle());
        String tempDestination = config.getString("tempDestination", "");
        destination = config.getString("destination", "/tmp");
        String assemblyWF = config.getString("assemblyWF", "assemblyWF");
        String metadataFileName = config.getString("metadataFileName", "stubContentMetadata.xml");
        String apiBaseUrl = config.getString("apiBaseUrl", "http://example.com/");
        //        String username = config.getString("username", "");
        //        String password = config.getString("password", "");

        String objectId = null;
        String contentType = null;
        String resourceType = null;
        Path exportRootFolder;

        for (Processproperty property : process.getEigenschaften()) {
            if (property.getTitel().equalsIgnoreCase("objectId")) {
                objectId = property.getWert();
            } else if (property.getTitel().equalsIgnoreCase("contentType")) {
                contentType = property.getWert();
            } else if (property.getTitel().equalsIgnoreCase("objectType")) {
                resourceType = property.getWert();
            }
        }
        if (objectId == null) {
            Helper.setFehlerMeldung("No objectId found, aborting.");
            log.error("No objectId found, export canceled: " + process.getTitel());
            problems.add("No objectId found, export canceled: " + process.getTitel());
            return false;
        }
        if (contentType == null) {
            Helper.setFehlerMeldung("No contentType found, aborting");
            log.error("No contentType found, export canceled: " + process.getTitel());
            problems.add("No contentType found, export canceled: " + process.getTitel());
            return false;
        }
        String originalObjectId = objectId;
        if (objectId.contains(":")) {
            objectId = objectId.substring(objectId.indexOf(":") + 1);
        }
        if (objectId.length() == 11) {
            //            String firstPart = objectId.substring(0, 2);
            //            String secondPart = objectId.substring(2, 5);
            //            String thirdPart = objectId.substring(5, 7);
            //            String forthPart = objectId.substring(7);
            exportRootFolder = Paths.get(destination, objectId.substring(0, 2), objectId.substring(2, 5), objectId.substring(5, 7), objectId
                    .substring(7), objectId);
        } else {
            Helper.setFehlerMeldung("ObjectId has unexpected length, aborting.");
            log.error("ObjectId has unexpected length, export canceled: " + process.getTitel());
            problems.add("ObjectId has unexpected length, export canceled: " + process.getTitel());
            return false;
        }
        Path exportfolder = Paths.get(exportRootFolder.toString(), "content");
        Path metadatafolder = Paths.get(exportRootFolder.toString(), "metadata");

        Files.createDirectories(exportfolder);
        Files.createDirectories(metadatafolder);
        List<String> imageFileNames = null;
        List<String> altoFileNames = null;
        List<String> pdfFileNames = null;
        // copy all images from media folder

        Path imageMediaFolder = Paths.get(process.getImagesTifDirectory(false));
        if (Files.exists(imageMediaFolder)) {
            StorageProvider.getInstance().copyDirectory(imageMediaFolder, exportfolder);
            imageFileNames = StorageProvider.getInstance().list(process.getImagesTifDirectory(false), NIOFileUtils.fileFilter);

            for (String filename : imageFileNames) {
                Path source = Paths.get(imageMediaFolder.toString(), filename);
                Path target = Paths.get( exportfolder.toString(), filename);
                long checksumSrc = StorageProvider.getInstance().checksumMappedFile(source.toString());
                long checksumDest = StorageProvider.getInstance().checksumMappedFile(target.toString());
                if (checksumSrc != checksumDest) {
                    Helper.setFehlerMeldung("Checksum error while validating images, aborting.");
                    log.error("Checksum error while validating images: " + target.toString());
                    problems.add("Checksum error while validating images: " + target.toString());
                    return false;
                }
            }
        }

        // copy all alto files from ocr folder
        Path ocrFolder = Paths.get(process.getOcrAltoDirectory());
        if (Files.exists(ocrFolder)) {
            StorageProvider.getInstance().copyDirectory(ocrFolder, exportfolder);
            altoFileNames = StorageProvider.getInstance().list(process.getOcrAltoDirectory(), NIOFileUtils.fileFilter);

            for (String filename : altoFileNames) {
                Path source = Paths.get(ocrFolder.toString(), filename);
                Path target = Paths.get( exportfolder.toString(), filename);
                long checksumSrc = StorageProvider.getInstance().checksumMappedFile(source.toString());
                long checksumDest = StorageProvider.getInstance().checksumMappedFile(target.toString());
                if (checksumSrc != checksumDest) {
                    Helper.setFehlerMeldung("Checksum error while validating alto files, aborting.");
                    log.error("Checksum error while validating alto files: " + target.toString());
                    problems.add("Checksum error while validating alto files: " + target.toString());
                    return false;
                }
            }

        }

        // copy all pdf files
        Path pdfFolder = Paths.get(process.getOcrPdfDirectory());
        if (Files.exists(pdfFolder)) {
            StorageProvider.getInstance().copyDirectory(pdfFolder, exportfolder);
            pdfFileNames = StorageProvider.getInstance().list(process.getOcrPdfDirectory(), NIOFileUtils.fileFilter);
            for (String filename : pdfFileNames) {
                Path source = Paths.get(pdfFolder.toString(), filename);
                Path target = Paths.get( exportfolder.toString(), filename);
                long checksumSrc = StorageProvider.getInstance().checksumMappedFile(source.toString());
                long checksumDest = StorageProvider.getInstance().checksumMappedFile(target.toString());
                if (checksumSrc != checksumDest) {
                    Helper.setFehlerMeldung("Checksum error while validating pdf files, aborting.");
                    log.error("Checksum error while validating pdf files: " + target.toString());
                    problems.add("Checksum error while validating pdf files: " + target.toString());
                    return false;
                }
            }
        }

        // generate one big pdf for all single page PDFs
        if (pdfFileNames != null && pdfFileNames.size() > 0) {
            mergePdfFiles(pdfFolder, pdfFileNames, exportfolder, objectId);
        }

        // create metadata file
        Document document = createMetadataFile(contentType, resourceType, imageFileNames, altoFileNames, pdfFileNames, objectId);

        // save xml file
        XMLOutputter xmlOutput = new XMLOutputter();
        xmlOutput.setFormat(Format.getPrettyFormat());
        xmlOutput.output(document, new FileWriter(Paths.get(metadatafolder.toString(), metadataFileName).toString()));

        // if the xml shall be saved additional into a temporary folder
        if (tempDestination != null && tempDestination.length() > 0) {
            xmlOutput = new XMLOutputter();
            xmlOutput.setFormat(Format.getPrettyFormat());
            xmlOutput.output(document, new FileWriter(Paths.get(tempDestination, "dor_export_" + objectId + ".xml").toString()));
        }

        int delay = config.getInt("delay", 0);
        if (delay > 0) {
            TimeUnit.SECONDS.sleep(delay);
        }

        // call api
        Client client = ClientBuilder.newClient();
        WebTarget base = client.target(apiBaseUrl);
        WebTarget target = base.path(originalObjectId).path(assemblyWF);
        Builder requestBuilder = target.request();
        log.debug("Sending POST request to " + requestBuilder);
        //        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
        //            String token = username + ":" + password;
        //            String authenticationCode = "BASIC " + DatatypeConverter.printBase64Binary(token.getBytes("UTF-8"));
        //            requestBuilder.header("Authorization", authenticationCode);
        //        }
        Response response = requestBuilder.post(null);
        StatusType type = response.getStatusInfo();
        int statuscode = type.getStatusCode();
        if (statuscode >= 200 && statuscode < 300) {
            Helper.setMeldung("API call was successful: " + type.getReasonPhrase() + " (" + type.getStatusCode() + ")");
            return true;
        } else {
            Helper.setFehlerMeldung("Something went wrong: " + type.getReasonPhrase() + " (" + type.getStatusCode() + ")");
            problems.add("Something went wrong: " + type.getReasonPhrase() + " (" + type.getStatusCode() + ")");
            return false;
        }

    }

    /**
     * create the metadata xml file for the images, the alto and the pdf files
     * 
     * @param contentType
     * @param resourceType
     * @param imageFileNames
     * @param altoFileNames
     * @param pdfFileNames
     * @param objectId
     * @return
     */
    private Document createMetadataFile(String contentType, String resourceType, List<String> imageFileNames, List<String> altoFileNames,
            List<String> pdfFileNames, String objectId) {
        Document doc = new Document();

        Element content = new Element(contentString);

        doc.setRootElement(content);

        content.setAttribute(typeString, contentType);

        if (imageFileNames != null) {

            if (altoFileNames != null && imageFileNames.size() == altoFileNames.size()) {

                for (int index = 0; index < imageFileNames.size(); index++) {
                    Element resource = new Element(resourceString);
                    content.addContent(resource);

                    // create image entry
                    Element label = new Element(labelString);
                    label.setText("Page " + (index + 1));
                    String imageName = imageFileNames.get(index);
                    Element imageFile = new Element(fileString);
                    imageFile.setAttribute(nameString, imageName);
                    resource.addContent(label);
                    resource.addContent(imageFile);

                    // create pdf entry
                    if (pdfFileNames != null) {
                        String pdfName = pdfFileNames.get(index);
                        Element pdfFile = new Element(fileString);
                        pdfFile.setAttribute(nameString, pdfName);
                        resource.addContent(pdfFile);
                    }

                    // create alto entry
                    String altoName = altoFileNames.get(index);
                    Element altoFile = new Element(fileString);
                    altoFile.setAttribute(nameString, altoName);
                    altoFile.setAttribute("role", "transcription");
                    altoFile.setAttribute("publish", "yes");
                    altoFile.setAttribute("preserve", "yes");
                    altoFile.setAttribute("shelve", "yes");
                    resource.addContent(altoFile);
                }

            } else {
                for (int index = 0; index < imageFileNames.size(); index++) {
                    String imageName = imageFileNames.get(index);
                    Element resource = new Element(resourceString);
                    content.addContent(resource);
                    // create Label?
                    Element imageFile = new Element(fileString);
                    imageFile.setAttribute(nameString, imageName);
                    resource.addContent(imageFile);
                }
            }

        }

        // add one pdf entry for the all-pages-pdf
        if (pdfFileNames != null) {
            Element resource = new Element(resourceString);
            Element file = new Element(fileString);
            file.setAttribute(nameString, objectId + ".pdf");
            resource.addContent(file);
            content.addContent(resource);
        }

        return doc;
    }

    /**
     * Merge multiple PDF files into one single file
     * 
     * @param pdfFolder
     * @param pdfFileNames
     * @param exportPath
     * @param objectId
     * @throws IOException
     */
    private void mergePdfFiles(Path pdfFolder, List<String> pdfFileNames, Path exportPath, String objectId) throws IOException {
        try {
            PDFMergerUtility PDFmerger = new PDFMergerUtility();
            String exportPdf = exportPath.toString() + File.separator + objectId + ".pdf";
            PDFmerger.setDestinationFileName(exportPdf);
            // add all pdf files
            for (String pdf : pdfFileNames) {
                File file = new File(pdfFolder.toFile(), pdf);
                PDFmerger.addSource(file);
            }
            // merge the pdf files now
            PDFmerger.mergeDocuments();
        } catch (Exception e) {
            throw new IOException("Error occured during the merge to a single PDF file", e);
        }
    }

    public static void main(String[] args) {
        String objectId = "druid:bb018zb8894";
        if (objectId.contains(":")) {
            objectId = objectId.substring(objectId.indexOf(":") + 1);
        }

        String destination = "/assembly";
        Path exportfolder = Paths.get(destination, objectId.substring(0, 2), objectId.substring(2, 5), objectId.substring(5, 7), objectId.substring(
                7), objectId, "content");

        System.out.println(exportfolder.toString());
    }

    @Override
    public void setExportFulltext(boolean exportFulltext) {
    }

    @Override
    public void setExportImages(boolean exportImages) {
    }

    public String getDescription() {
        return getTitle();
    }

    @Override
    public List<String> getProblems() {
        return problems;
    }
}
