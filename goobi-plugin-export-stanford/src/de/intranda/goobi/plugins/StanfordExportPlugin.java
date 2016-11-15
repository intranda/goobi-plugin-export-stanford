package de.intranda.goobi.plugins;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
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

    @Override
    public PluginType getType() {
        return PluginType.Export;
    }

    @Override
    public String getTitle() {
        return "plugin-intranda-export-stanford";
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        // TODO get from configuration?
        return startExport(process, "/assembly");
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {
        Path exportfolder;
        String objectId = null;
        String contentType = null;
        String resourceType = null;
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
            return false;
        }
        if (contentType == null) {
            Helper.setFehlerMeldung("No contentType found, aborting");
            log.error("No contentType found, export canceled: " + process.getTitel());
            return false;
        }

        if (objectId.contains(":")) {
            objectId = objectId.substring(objectId.indexOf(":") + 1);
        }
        if (objectId.length() == 11) {
            //            String firstPart = objectId.substring(0, 2);
            //            String secondPart = objectId.substring(2, 5);
            //            String thirdPart = objectId.substring(5, 7);
            //            String forthPart = objectId.substring(7);
            exportfolder = Paths.get(destination, objectId.substring(0, 2), objectId.substring(2, 5), objectId.substring(5, 7), objectId.substring(7),
                    objectId, "content");
        } else {
            Helper.setFehlerMeldung("ObjectId has unexpected length, aborting.");
            log.error("ObjectId has unexpected length, export canceled: " + process.getTitel());
            return false;
        }
        Files.createDirectories(exportfolder);
        List<String> imageFileNames = null;
        List<String> txtFileNames = null;
        List<String> pdfFileNames = null;
        // copy all images from media folder

        Path imageMediaFolder = Paths.get(process.getImagesTifDirectory(false));
        if (Files.exists(imageMediaFolder)) {
            NIOFileUtils.copyDirectory(imageMediaFolder, exportfolder);
            imageFileNames = NIOFileUtils.list(process.getImagesTifDirectory(false), NIOFileUtils.fileFilter);
        }
        // copy all txt files from ocr folder

        Path ocrFolder = Paths.get(process.getTxtDirectory());
        if (Files.exists(ocrFolder)) {
            NIOFileUtils.copyDirectory(ocrFolder, exportfolder);
            txtFileNames = NIOFileUtils.list(process.getTxtDirectory(), NIOFileUtils.fileFilter);
        }

        // copy pdf file
        Path pdfFolder = Paths.get(process.getPdfDirectory());
        if (Files.exists(pdfFolder)) {
            NIOFileUtils.copyDirectory(pdfFolder, exportfolder);
            pdfFileNames = NIOFileUtils.list(process.getPdfDirectory(), NIOFileUtils.fileFilter);
        }

        // create metadata file

        Document document = createMetadataFile(contentType, resourceType, imageFileNames, txtFileNames, pdfFileNames);

        // save xml file
        XMLOutputter xmlOutput = new XMLOutputter();
        xmlOutput.setFormat(Format.getPrettyFormat());
        xmlOutput.output(document, new FileWriter(Paths.get(exportfolder.toString(), "contentMetadata.xml").toString()));

        // call api

        //        String apiURL = process.getProjekt().getDmsImportErrorPath();

        return true;
    }

    private static final String contentString = "content";
    private static final String typeString = "type";
    private static final String nameString = "name";
    private static final String fileString = "file";
    private static final String labelString = "label";
    private static final String resourceString = "resource";

    private Document createMetadataFile(String contentType, String resourceType, List<String> imageFileNames, List<String> txtFileNames,
            List<String> pdfFileNames) {
        Document doc = new Document();

        Element content = new Element(contentString);

        doc.setRootElement(content);
        // TODO book_with_pdf, book_as_image inclear

        if (contentType.equals("image")) {
            content.setAttribute(typeString, "simple_image");
        } else if (contentType.equals("file")) {
            content.setAttribute(typeString, "file");
        } else if (contentType.equals("book")) {
            content.setAttribute(typeString, "simple_book");

        } else if (contentType.equals("map")) {
            content.setAttribute(typeString, "map");
        } else {
            // default value is simple_image 
            content.setAttribute(typeString, "simple_image");
        }
        // TODO set publish="true" preserve="true" shelve="true" ?
        if (imageFileNames != null) {

            if (txtFileNames != null && imageFileNames.size() == txtFileNames.size()) {

                for (int index = 0; index < imageFileNames.size(); index++) {
                    String imageName = imageFileNames.get(index);
                    String txtName = txtFileNames.get(index);

                    Element resource = new Element(resourceString);
                    content.addContent(resource);

                    // create Label?
                    Element label = new Element(labelString);
                    Element imageFile = new Element(fileString);
                    Element txtFile = new Element(fileString);
                    label.setText("Page " + (index + 1));

                    imageFile.setAttribute(nameString, imageName);
                    txtFile.setAttribute(nameString, txtName);
                    resource.addContent(label);
                    resource.addContent(imageFile);
                    resource.addContent(txtFile);
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

        if (pdfFileNames != null) {
            Element resource = new Element(resourceString);
            Element file = new Element(fileString);
            file.setAttribute(nameString, pdfFileNames.get(0));
            resource.addContent(file);

            content.addContent(resource);
        }

        return doc;
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
}
