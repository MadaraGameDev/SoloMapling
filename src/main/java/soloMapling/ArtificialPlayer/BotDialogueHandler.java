package soloMapling.ArtificialPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.ArtificialPlayer.BotTypes.DiceBot;

import java.util.Map;
import java.io.FileReader;
import java.io.IOException;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotDialogue;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.server.SoloMaplingUtilities.random;

public class BotDialogueHandler {

    private Character chr;

    public static class DialogueConstructor {
        private List<String> dialogue;
        private final List<Integer> emotes;
        private final long duration;

        public DialogueConstructor(List<String> dialogue, List<Integer> emotes, long duration) {
            this.dialogue = dialogue;
            this.emotes = emotes;
            this.duration = duration;
        }

        private void setDialogue(List<String> newDialogue) {
            this.dialogue = newDialogue;
        }

        public List<String> getDialogue() {
            return this.dialogue;
        }

        public String getDialogue(int i) {
            return getDialogue().get(i);
        }

        public Integer getEmote() {
            return emotes.get(random.nextInt(emotes.size()));
        }

        public List<Integer> getEmotes() {
            return emotes;
        }

        private long getDuration() {
            return this.duration;
        }
    }

    public BotDialogueHandler(Character chr) {
        this.chr = chr;
    }

    public void executeBotFlavorDialogue(String DialogueNodeName, BotSM botSM) {
        runBotFlavorDialogue(botSM.getChr(), getDialogueCon(botSM.dialoguePath, botSM.botType, DialogueNodeName));
    }

    public void executeBotDialogueWithReplacementStrings(String DialogueNodeName, Map<String, String> replacements, BotSM botSM) {
        runBotDialogue(botSM.getChr(), getDialogueConWithReplacedStrings(botSM.dialoguePath, botSM.botType, DialogueNodeName, replacements));
    }

    public void executeBotDialogue(String DialogueNodeName, BotSM botSM) {
        runBotDialogue(botSM.getChr(), getDialogueCon(botSM.dialoguePath, botSM.botType, DialogueNodeName));
    }

    public void listOptions(Character player, BotSM botSM) {
        // Check if the instance is of type DiceBot
        if (botSM instanceof DiceBot) {
            System.out.println("Dice bot instance");
            DiceBot diceBot = (DiceBot) botSM; // Downcast to DiceBot
            diceBot.displayCommands(player);   // Now you can call DiceBot methods
        } else {
            System.out.println("Not a DiceBot instance");
            botSM.displayCommands(player);
        }

    }

    public static Map<String, Object> readDialogueYaml(String dialoguePack, String dialogueType, String dialogueNode) {
        String dialoguePackBase = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/";
        String filePath = String.format("%s%s", dialoguePackBase, dialoguePack);

        Map<String, Object> dialogueConstructorNode = null;
        try {
            YamlReader reader = new YamlReader(new FileReader(filePath));

            // Read the root node
            Map<String, Object> root = (Map<String, Object>) reader.read();
            Map<String, Object> BotTypeNode = (Map<String, Object>) root.get(dialogueType);
            dialogueConstructorNode = (Map<String, Object>) BotTypeNode.get(dialogueNode);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return dialogueConstructorNode;
    }

    public static DialogueConstructor getDialogueCon(String BotTypeDialoguePath, String BotType, String DialogueNodeName) {
        Map<String, Object> dialogMap = readDialogueYaml(BotTypeDialoguePath, BotType, DialogueNodeName);
        if (dialogMap == null) {
            return null;
        }
        List<String> textList = (List<String>) dialogMap.get("text");
        List<Integer> emotes = parseEmotes(dialogMap.get("emote"));
        long duration = (convertToInt(dialogMap.get("wait")) * 1000); // milliseconds
        return new DialogueConstructor(textList, emotes, duration);
    }

    public static DialogueConstructor getDialogueConWithReplacedStrings(String BotTypeDialoguePath, String BotType, String DialogueNodeName, Map<String, String> replacements) {
        DialogueConstructor og = getDialogueCon(BotTypeDialoguePath, BotType, DialogueNodeName);
        og.setDialogue(replaceStrings(og.getDialogue(), replacements));
        return og;
    }

    public static List<String> replaceStrings(List<String> inputList, Map<String, String> replacements) {
        if (inputList == null || replacements == null) {
            throw new IllegalArgumentException("Input list and replacements map cannot be null");
        }

        List<String> resultList = new ArrayList<>();
        for (String str : inputList) {
            String modifiedStr = str;
            // Replace all keys in the map with their corresponding values
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                modifiedStr = modifiedStr.replace(entry.getKey(), entry.getValue());
            }
            resultList.add(modifiedStr);
        }
        return resultList;
    }

    public static String getRandomDialogueLine(BotSM botSM, String DialogueNodeName) {
        DialogueConstructor dialog = getDialogueCon(botSM.dialoguePath, botSM.botType, DialogueNodeName);
        return dialog.getDialogue().get(random.nextInt(dialog.getDialogue().size()));
    }

    public static void runBotDialogue(Character character, DialogueConstructor dialog) {
        runDialogue(character, dialog, dialog.getDialogue());
    }

    public static void runBotFlavorDialogue(Character character, DialogueConstructor dialog) {
        String randomText = dialog.getDialogue().get(random.nextInt(dialog.getDialogue().size()));
        runDialogue(character, dialog, Collections.singletonList(randomText));
    }

    private static void runDialogue(Character character, DialogueConstructor dialog, List<String> textToShow) {
        if (dialog == null) {
            return;
        }
        BotDialogue(character, textToShow);
        BotEmote(character, dialog.getEmote());
        BotHelpers.sleepAmountSeconds(dialog.getDuration());
    }

    private static List<Integer> parseEmotes(Object obj) {
        if (obj instanceof List) {
            List<?> rawList = (List<?>) obj;
            List<Integer> emotes = new ArrayList<>();
            for (Object item : rawList) {
                emotes.add(convertToInt(item));
            }
            return emotes;
        }
        return Collections.singletonList(convertToInt(obj));
    }

    private static Integer convertToInt(Object obj) {
        if (obj instanceof Integer) {
            return (Integer) obj;  // Directly cast if it's already an Integer
        } else if (obj instanceof Long) {
            return ((Long) obj).intValue();  // Convert Long to int
        } else if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);  // Convert String to int
            } catch (NumberFormatException e) {
                System.err.println("Error converting String to int: " + obj);
            }
        }
        return 0;  // Default value if unable to convert
    }
    
}
