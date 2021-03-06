package uk.co.innoxium.baldursgate.bg3m.installer;

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
import uk.co.innoxium.baldursgate.BaldursGateModInstaller;
import uk.co.innoxium.baldursgate.bg3m.MetaInfo;
import uk.co.innoxium.candor.mod.Mod;
import uk.co.innoxium.candor.module.AbstractModule;
import uk.co.innoxium.candor.util.NativeDialogs;
import uk.co.innoxium.candor.util.Utils;
import uk.co.innoxium.cybernize.archive.Archive;
import uk.co.innoxium.cybernize.archive.ArchiveBuilder;
import uk.co.innoxium.cybernize.json.JsonUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class PAKInstaller {

    private final AbstractModule module;
    private final String xPath = "//save/region/node/children/node";
    private final BaldursGateModInstaller.ModType type;

    public PAKInstaller(AbstractModule module, BaldursGateModInstaller.ModType type) {

        this.module = module;
        this.type = type;
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
     * @param mod - The mod to uninstall
     * @return true if already disabled, or uninstalled correctly
     */
    public boolean uninstallBG3M(Mod mod) {

        if(mod.getState() == Mod.State.DISABLED) return true;

        try {

            if(type == BaldursGateModInstaller.ModType.PAK) {

                File playerProfile = new File(BG3Settings.playerProfile);
                File modSettingsBackup = new File(playerProfile, "modsettings.backup.lsx");
                if(!modSettingsBackup.canWrite()) modSettingsBackup.setWritable(true);
                FileUtils.copyFile(new File(playerProfile, "modsettings.lsx"), modSettingsBackup);

                // Extract to temp location
                File temp = Files.createTempDirectory("bg3").toFile();
                Archive archive = new ArchiveBuilder(mod.getFile()).type(ArchiveBuilder.ArchiveType.SEVEN_ZIP).outputDirectory(temp).build();
                archive.extract();

                File info = new File(temp, "info.json");
                JsonObject contents = JsonUtil.getObjectFromPath(info.toPath());

                JsonArray modsArray = JsonUtil.getArray(contents, "mods");
                if(modsArray == null) {

                    // Attempt to get from caps too
                    modsArray = JsonUtil.getArray(contents, "Mods");
                }

                if(modsArray != null) {

                    modsArray.forEach(jsonElement -> {

                        MetaInfo bg3Mod;
                        if(contents.has("MD5"))
                            bg3Mod = new MetaInfo(MetaInfo.MetaType.V3).fromJson(jsonElement.getAsJsonObject());
                        else
                            bg3Mod = new MetaInfo(MetaInfo.MetaType.V2).fromJson(jsonElement.getAsJsonObject());
//                        BG3Mod bg3Mod = BG3Mod.fromJson((JsonObject) jsonElement);
                        assert bg3Mod != null;
                        removeXMLElements(bg3Mod);
                    });
                } else {

                    MetaInfo bg3Mod = new MetaInfo(MetaInfo.MetaType.V1).fromJson(contents);
                    removeXMLElements(bg3Mod);
                }
            }
            mod.getAssociatedFiles().forEach(element -> {

                FileUtils.deleteQuietly(new File(element.getAsString()));
            });
            return true;
        } catch (IOException e) {

            e.printStackTrace();
        }
        return false;
    }

    private boolean removeXMLElements(MetaInfo mod) {

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
    public CompletableFuture<Boolean> installBG3M(Mod mod) {

        //Steps
        try {

            if(type == BaldursGateModInstaller.ModType.PAK) {

                File playerProfile = new File(BG3Settings.playerProfile);
                File modSettings = new File(playerProfile, "modsettings.lsx");
                if(!modSettings.exists()) {

                    NativeDialogs.showErrorMessage("ModSettings.lsx does not exist. Mod installation cannot continue.\nTry running the game to generate this file.\n If this still isn't working, please contact us on Discord, which can be found in the \"About\" menu.");
                    return CompletableFuture.failedFuture(new FileNotFoundException("ModSettings.lsx not found"));
                }
                File modSettingsBackup = new File(playerProfile, "modsettings.backup.lsx");
                if(!modSettingsBackup.canWrite()) modSettingsBackup.setWritable(true);
                FileUtils.copyFile(new File(playerProfile, "modsettings.lsx"), modSettingsBackup);
                // Extract to temp location
                File temp = Files.createTempDirectory("bg3").toFile();
                Archive archive = new ArchiveBuilder(mod.getFile()).type(ArchiveBuilder.ArchiveType.SEVEN_ZIP).outputDirectory(temp).build();
                archive.extract();

                File info = new File(temp, "info.json");
                JsonObject contents = JsonUtil.getObjectFromPath(info.toPath());

                JsonArray modsArray = JsonUtil.getArray(contents, "mods");
                if(modsArray == null) {

                    // Try to get mods array from uppercase too
                    modsArray = JsonUtil.getArray(contents, "Mods");
                }

                JsonArray associatedPaks = new JsonArray();

                // For mods which use the correct info.json
                if(modsArray != null) {

                    modsArray.forEach(jsonElement -> {

                        MetaInfo bg3Mod;
                        File modPak;
                        if(contents.has("MD5")) {

                            bg3Mod = new MetaInfo(MetaInfo.MetaType.V3).fromJson(jsonElement.getAsJsonObject());
//                            Arrays.stream(temp.listFiles()).iterator().forEachRemaining(System.out::println);
                            modPak = temp.listFiles((dir, name) -> name.contains(".pak"))[0];
                        } else {

                            bg3Mod = new MetaInfo(MetaInfo.MetaType.V2).fromJson(jsonElement.getAsJsonObject());
                            modPak = new File(temp, bg3Mod.getFolder() + ".pak");
                        }

                        createAndMergeXML(bg3Mod);

                        try {

                            if(bg3Mod.getType() == MetaInfo.MetaType.V2) {

                                File newPakFile = new File(module.getModsFolder(), bg3Mod.getFolder() + ".pak");
                                associatedPaks.add(newPakFile.getAbsolutePath());
                                FileUtils.copyFile(modPak, newPakFile);
                            } else {

                                File newPakFile = new File(module.getModsFolder(), modPak.getName());
                                if(associatedPaks.size() < 1)
                                    associatedPaks.add(newPakFile.getAbsolutePath());
                                FileUtils.copyFile(modPak, newPakFile);
                            }
                        } catch (IOException e) {

                            e.printStackTrace();
                        }
                    });
                } else {

                    // For mods which use the incorrect mods.json - Support for a while
                    MetaInfo bg3Mod = new MetaInfo(MetaInfo.MetaType.V1).fromJson(contents);
//                    BG3Mod bg3Mod = BG3Mod.fromJson(contents);
                    createAndMergeXML(bg3Mod);

                    try {

                        File modPak = new File(temp, bg3Mod.getFolder() + ".pak");
                        File newPakFile = new File(module.getModsFolder(), bg3Mod.getFolder() + ".pak");
                        associatedPaks.add(newPakFile.getAbsolutePath());
                        FileUtils.copyFile(modPak, newPakFile);
                    } catch (IOException e) {

                        e.printStackTrace();
                    }
                }
                mod.setAssociatedFiles(associatedPaks);

                return CompletableFuture.completedFuture(true);
            } else if(type == BaldursGateModInstaller.ModType.PAK_ONLY) {

                // Installer for pak only mods, such as IgnoreMessage
                File temp = Files.createTempDirectory("bg3").toFile();
                Archive archive = new ArchiveBuilder(mod.getFile()).type(ArchiveBuilder.ArchiveType.SEVEN_ZIP).outputDirectory(temp).build();
                archive.extract();

                JsonArray associatedPaks = new JsonArray();

                for (File pak : temp.listFiles()) {

                    if(Utils.getExtension(pak).equalsIgnoreCase("pak")) {

                        File newPakFile = new File(module.getModsFolder(), pak.getName());
                        FileUtils.copyFile(pak, newPakFile);
                        associatedPaks.add(newPakFile.getAbsolutePath());
                    }
                }
                mod.setAssociatedFiles(associatedPaks);
                return CompletableFuture.completedFuture(associatedPaks.size() > 0);
            }
            return CompletableFuture.completedFuture(false);
        } catch (IOException e) {

            e.printStackTrace();
            return CompletableFuture.failedFuture(e);
        }
    }

    private void createAndMergeXML(MetaInfo mod) {

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
        if(!modsettings.canWrite()) modsettings.setWritable(true);
        OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(modsettings), StandardCharsets.UTF_8);

        OutputFormat format = OutputFormat.createPrettyPrint();
        format.setEncoding("UTF-8");
        XMLWriter xmlWriter = new XMLWriter(writer, format);

        document.normalize();
        xmlWriter.write(document);
        xmlWriter.flush();

        xmlWriter.close();
    }
}
