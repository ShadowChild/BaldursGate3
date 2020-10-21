package uk.co.innoxium.baldursgate.bg3m;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.xml.sax.SAXException;
import uk.co.innoxium.baldursgate.BG3Settings;
import uk.co.innoxium.baldursgate.BaldursGateModule;
import uk.co.innoxium.candor.mod.Mod;
import uk.co.innoxium.candor.module.AbstractModule;
import uk.co.innoxium.cybernize.archive.Archive;
import uk.co.innoxium.cybernize.archive.ArchiveBuilder;
import uk.co.innoxium.cybernize.json.JsonUtil;
import uk.co.innoxium.cybernize.util.FileUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BG3MInstaller {

    private final AbstractModule module;
    private final String xPath = "//save/region/node/children/node";

    public BG3MInstaller(AbstractModule module) {

        this.module = module;
    }

    /**
     * Runs though the uninstallation steps
     * - backup modsettings.lsx
     * - Read info.json for each mod details
     * - Determine which nodes to remove from modsettings.lsx
     * - remove nodes
     * - write xml
     * - fallback on modsettings.backup.lsx
     * - remove paks
     * @param mod
     * @return
     */
    public boolean uninstallBG3M(Mod mod) {

        try {

            File playerProfile = new File(BG3Settings.playerProfile);
            FileUtils.copyFile(new File(playerProfile, "modsettings.lsx"), new File(playerProfile, "modsettings.backup.lsx"));

            // Extract to temp location
            File temp = Files.createTempDirectory("bg3").toFile();
            Archive archive = new ArchiveBuilder(mod.getFile()).type(ArchiveBuilder.ArchiveType.SEVEN_ZIP).outputDirectory(temp).build();
            archive.extract();

            File info = new File(temp, "info.json");
            JsonObject contents = JsonUtil.getObjectFromPath(info.toPath());

            JsonArray modsArray = JsonUtil.getArray(contents, "mods");

            modsArray.forEach(jsonElement -> {

                BG3Mod bg3Mod = BG3Mod.fromJson((JsonObject)jsonElement);
                removeXMLElements(bg3Mod);
            });
            mod.getAssociatedFiles().forEach(element -> {

                FileUtils.deleteQuietly(new File(element.getAsString()));
            });
            return true;
        } catch (IOException e) {

            e.printStackTrace();
        }
        return false;
    }

    private boolean removeXMLElements(BG3Mod mod) {

        File modsetings = new File(BG3Settings.playerProfile, "modsettings.lsx");

        try {

            Document doc = getSaxReader().read(modsetings);
            // Get the element for the Mods node
            AtomicReference<Element> mods = new AtomicReference<>();
            AtomicReference<Element> modOrder = new AtomicReference<>();
            List<Node> nodes = doc.selectNodes(xPath);
            nodes.forEach(node -> {

                Element element = (Element)node;
                element.attributeIterator().forEachRemaining(attr -> {

                    if(attr.getName().equalsIgnoreCase("id")) {

                        System.out.println(element.getName());
                        if(attr.getValue().equalsIgnoreCase("mods")) {

                            mods.set(element);
                        }
                        if(attr.getValue().equalsIgnoreCase("modorder")) {

                            modOrder.set(element);
                        }
                    }
                });
            });
            Element modChildren = (Element)mods.get().selectSingleNode("children");
            mod.removeModShortDesc(modChildren);
            Element modOrderChildren = (Element)modOrder.get().selectSingleNode("children");
            if(modOrderChildren == null) {

                modOrderChildren = modOrder.get().addElement("children");
            }
            mod.removeModOrder(modOrderChildren);

            writeXMLToFile(doc);
            return true;
        } catch (DocumentException | IOException e) {

            e.printStackTrace();
            return false;
        }
    }

    /**
     * Runs through the installation steps
     * - Extract mod to temp folder
     * - Read contents of info.json
     * - build two XML nodes from json, id's: Module, ModuleShortDesc
     * - try to add these to XML - restore to default if anything breaks
     * - finally copy pak to mods folder
     * @param mod - The mod to install
     * @return true if installed correctly
     */
    public boolean installBG3M(Mod mod) {

        //Steps
        try {

            File playerProfile = new File(BG3Settings.playerProfile);
            FileUtils.copyFile(new File(playerProfile, "modsettings.lsx"), new File(playerProfile, "modsettings.backup.lsx"));
            // Extract to temp location
            File temp = Files.createTempDirectory("bg3").toFile();
            Archive archive = new ArchiveBuilder(mod.getFile()).type(ArchiveBuilder.ArchiveType.SEVEN_ZIP).outputDirectory(temp).build();
            archive.extract();

            File info = new File(temp, "info.json");
            JsonObject contents = JsonUtil.getObjectFromPath(info.toPath());

            JsonArray modsArray = JsonUtil.getArray(contents, "mods");

            JsonArray associatedPaks = new JsonArray();

            modsArray.forEach(jsonElement -> {

                BG3Mod bg3Mod = BG3Mod.fromJson(jsonElement.getAsJsonObject());
                File modPak = new File(temp, bg3Mod.folderName + ".pak");
                createAndMergeXML(bg3Mod);

                try {

                    File newPakFile = new File(module.getModsFolder(), bg3Mod.folderName + ".pak");
                    associatedPaks.add(newPakFile.getAbsolutePath());
                    FileUtils.copyFile(modPak, newPakFile);
                } catch (IOException e) {

                    e.printStackTrace();
                }
            });
            mod.setAssociatedFiles(associatedPaks);

            return true;
        } catch (IOException e) {

            e.printStackTrace();
            return false;
        }
    }

    private void createAndMergeXML(BG3Mod mod) {

        File modsetings = new File(BG3Settings.playerProfile, "modsettings.lsx");
        try {

            Document doc = getSaxReader().read(modsetings);

            // Get the element for the Mods node
            AtomicReference<Element> mods = new AtomicReference<>();
            AtomicReference<Element> modOrder = new AtomicReference<>();
            List<Node> nodes = doc.selectNodes(xPath);
            nodes.forEach(node -> {

                Element element = (Element)node;
                element.attributeIterator().forEachRemaining(attr -> {

                    if(attr.getName().equalsIgnoreCase("id")) {

                        if(attr.getValue().equalsIgnoreCase("mods")) {

                            mods.set(element);
                        }
                        if(attr.getValue().equalsIgnoreCase("modorder")) {

                            modOrder.set(element);
                        }
                    }
                });
            });
            Element modChildren = (Element)mods.get().selectSingleNode("children");
            mod.toModuleShortDesc(modChildren);
            Element modOrderChildren = (Element)modOrder.get().selectSingleNode("children");
            if(modOrderChildren == null) {

                modOrderChildren = modOrder.get().addElement("children");
            }
            mod.toModOrder(modOrderChildren);

            writeXMLToFile(doc);
        } catch (DocumentException | IOException e) {

            e.printStackTrace();
            try {

                FileUtils.copyFile(new File(BG3Settings.playerProfile, "modsettings.backup.lsx"), modsetings);
            } catch (IOException ioException) {

                ioException.printStackTrace();
            }
        }
    }

    private SAXReader getSaxReader() {

        SAXReader reader = SAXReader.createDefault();
        try {

            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", true);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", true);
        } catch (SAXException e) {

            e.printStackTrace();
        }
        reader.setEncoding(StandardCharsets.UTF_8.name());
        return reader;
    }

    private void writeXMLToFile(Document document) throws IOException {

        File modsettings = new File(BG3Settings.playerProfile, "modsettings.lsx");
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(modsettings), StandardCharsets.UTF_8);

        OutputFormat format = OutputFormat.createPrettyPrint();
        format.setEncoding("UTF-8");
        XMLWriter xmlWriter = new XMLWriter(writer, format);

        document.normalize();
        xmlWriter.write(document);
        xmlWriter.flush();

        xmlWriter.close();
    }

    public boolean verifyMod(Mod mod) {

        try(ZipFile zipFile = new ZipFile(mod.getFile())) {

            ZipEntry info = zipFile.getEntry("info.json");
            return info != null;
        } catch (IOException e) {

            e.printStackTrace();
            return false;
        }
    }
}
