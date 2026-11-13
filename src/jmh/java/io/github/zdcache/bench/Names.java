package io.github.zdcache.bench;

import java.util.Random;

final class Names {
    private static final String[] FIRST = {
        "Anna", "Maria", "Ingrid", "Kari", "Nora", "Emma", "Sofie", "Ida", "Sara", "Hanne", "Marit", "Liv", "Astrid", "Silje",
        "Thea", "Julie", "Mari", "Ane", "Eva", "Heidi", "Ole", "Per", "Lars", "Jan", "Knut", "Arne", "Bjorn", "Erik", "Jon",
        "Hans", "Nils", "Magnus", "Jonas", "Henrik", "Martin", "Kristian", "Andreas", "Thomas", "Petter", "Even", "Sindre",
        "Tor", "Geir", "Trond", "Rune", "Eirik", "Sander", "Mathias", "Aksel", "Olav"
    };
    private static final String[] LAST = {
        "Hansen", "Johansen", "Olsen", "Larsen", "Andersen", "Pedersen", "Nilsen", "Kristiansen", "Jensen", "Karlsen",
        "Johnsen", "Pettersen", "Eriksen", "Berg", "Haugen", "Hagen", "Johannessen", "Andreassen", "Jacobsen", "Dahl",
        "Jorgensen", "Halvorsen", "Henriksen", "Lund", "Sorensen", "Jakobsen", "Moen", "Gundersen", "Iversen", "Strand",
        "Solberg", "Svendsen", "Eide", "Knutsen", "Martinsen", "Paulsen", "Bakken", "Kristoffersen", "Mathisen", "Lie",
        "Amundsen", "Nguyen", "Rasmussen", "Ali", "Lunde", "Solheim", "Berge", "Moe", "Nygaard", "Bakke", "Kristensen",
        "Fredriksen", "Holm", "Lien", "Hauge", "Christensen", "Andresen", "Nielsen", "Knudsen", "Evensen"
    };

    private Names() { }

    static String person(Random random) {
        String first = FIRST[random.nextInt(FIRST.length)];
        String middle = random.nextInt(3) == 0 ? " " + FIRST[random.nextInt(FIRST.length)] : "";
        return first + middle + " " + LAST[random.nextInt(LAST.length)];
    }

    static String typo(String text, Random random) {
        int at = 1 + random.nextInt(text.length() - 2);
        return switch (random.nextInt(4)) {
            case 0 -> text.substring(0, at) + (char) ('a' + random.nextInt(26)) + text.substring(at + 1);
            case 1 -> text.substring(0, at) + text.charAt(at + 1) + text.charAt(at) + text.substring(at + 2);
            case 2 -> text.substring(0, at) + text.substring(at + 1);
            default -> text.substring(0, at) + (char) ('a' + random.nextInt(26)) + text.substring(at);
        };
    }
}
