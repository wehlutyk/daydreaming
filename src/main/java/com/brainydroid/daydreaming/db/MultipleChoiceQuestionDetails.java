package com.brainydroid.daydreaming.db;

import java.util.ArrayList;

public class MultipleChoiceQuestionDetails implements IQuestionDetails {

    @SuppressWarnings("UnusedDeclaration")
    private static String TAG = "MultipleChoiceQuestionDetails";

    @SuppressWarnings("FieldCanBeLocal")
    private String type = "MultipleChoice";
    @SuppressWarnings("UnusedDeclaration")
    private String text = null;
    @SuppressWarnings("UnusedDeclaration")
    private ArrayList<String> choices = new ArrayList<String>();

    @Override
    public String getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public ArrayList<String> getChoices() {
        return choices;
    }

}
