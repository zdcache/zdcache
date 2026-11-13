package io.github.zdcache.index;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditDistanceTest {
    @Test void anInsertionDeletionSubstitutionOrSwapOfNeighboursIsOneEdit() {
        assertEquals(0, EditDistance.between("andersen", "andersen"));
        assertEquals(1, EditDistance.between("andersen", "anddersen"));
        assertEquals(1, EditDistance.between("andersen", "andrsen"));
        assertEquals(1, EditDistance.between("andersen", "anderson"));
        assertEquals(1, EditDistance.between("andersen", "andresen"));
        assertEquals(2, EditDistance.between("andersen", "andreson"));
    }

    @Test void emptyTextsCountEveryCharacterOfTheOther() {
        assertEquals(0, EditDistance.between("", ""));
        assertEquals(4, EditDistance.between("", "berg"));
        assertEquals(4, EditDistance.between("berg", ""));
    }

    @Test void aSwappedPairIsNotEditedAgain() {
        assertEquals(3, EditDistance.between("ca", "abc"));
    }
}
