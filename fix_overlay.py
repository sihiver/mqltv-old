import re

with open("/home/dindin/AndroidStudioProjects/MQLTV-OLD/app/src/main/java/com/mqltv/PlayerChannelOverlayController.java", "r") as f:
    content = f.read()

content = content.replace("pickInitialCategoryIndex(state, currentUrl)", "pickInitialCategoryIndex(state, currentId)")
content = content.replace("adapter.setCurrentUrl(currentUrl)", "adapter.setCurrentId(currentId)")
content = content.replace("pickChannelToBind(listForCat, currentUrl)", "pickChannelToBind(listForCat, currentId)")
content = content.replace("adapter.findPositionByUrl(currentUrl)", "adapter.findPositionById(currentId)")

content = content.replace("private int pickInitialCategoryIndex(CategoryState state, String currentUrl) {", "private int pickInitialCategoryIndex(CategoryState state, int currentId) {")
content = content.replace("if (currentUrl != null && !currentUrl.trim().isEmpty()) {", "if (currentId != 0) {")
content = content.replace("if (c != null && currentUrl.equals(c.getUrl())) {", "if (c != null && currentId == c.getId()) {")

content = content.replace("private static Channel pickChannelToBind(List<Channel> list, String currentUrl) {", "private static Channel pickChannelToBind(List<Channel> list, int currentId) {")
content = content.replace("if (currentUrl != null) {", "if (currentId != 0) {")
content = content.replace("if (c != null && currentUrl.equals(c.getUrl())) return c;", "if (c != null && currentId == c.getId()) return c;")

# ChannelAdapter
content = content.replace("private String currentUrl;", "private int currentId;")
content = content.replace("""        void setCurrentUrl(String url) {
            // Only refresh the rows that changed (previous/current) to avoid full
            // adapter refresh which can cause focus loss during rapid navigation.
            String prev = currentUrl;
            if (prev == null ? url == null : prev.equals(url)) {
                currentUrl = url;
                return;
            }
            int prevPos = findPositionByUrl(prev);
            currentUrl = url;
            int newPos = findPositionByUrl(url);""", """        void setCurrentId(int id) {
            // Only refresh the rows that changed (previous/current) to avoid full
            // adapter refresh which can cause focus loss during rapid navigation.
            int prev = currentId;
            if (prev == id) {
                currentId = id;
                return;
            }
            int prevPos = findPositionById(prev);
            currentId = id;
            int newPos = findPositionById(id);""")

content = content.replace("""        int findPositionByUrl(String url) {
            if (url == null) return RecyclerView.NO_POSITION;
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c != null && url.equals(c.getUrl())) return i;
            }
            return RecyclerView.NO_POSITION;
        }""", """        int findPositionById(int id) {
            if (id == 0) return RecyclerView.NO_POSITION;
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c != null && id == c.getId()) return i;
            }
            return RecyclerView.NO_POSITION;
        }""")

content = content.replace("boolean isCurrent = c != null && currentUrl != null && currentUrl.equals(c.getUrl());", "boolean isCurrent = c != null && currentId != 0 && currentId == c.getId();")

with open("/home/dindin/AndroidStudioProjects/MQLTV-OLD/app/src/main/java/com/mqltv/PlayerChannelOverlayController.java", "w") as f:
    f.write(content)

