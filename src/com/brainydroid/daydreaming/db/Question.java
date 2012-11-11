package com.brainydroid.daydreaming.db;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import android.content.Context;
import android.location.Location;
import android.util.FloatMath;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.brainydroid.daydreaming.R;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;

// this class defines the general structure of questions and their answer.
// These attributes will be saved

// TODO: add some way to save the phone's timezone and the user's preferences
// about what times he allowed notifications to appear at.

public class Question {

	private static String TAG = "Question";

	// attributes inherent to the question
	@Expose private String _id;
	private String _category;
	private String _subcategory;
	private String _type;
	private String _mainText;
	private String _parametersText;
	private int _questionsVersion;

	// attributes dependent on the answer
	@Expose private String _status;
	@Expose private Answer _answer;
	@Expose private double _locationLatitude;
	@Expose private double _locationLongitude;
	@Expose private double _locationAltitude;
	@Expose private double _locationAccuracy;
	@Expose private long _timestamp;

	private transient Gson gson;

	public static final String PARAMETER_SPLITTER = "\\{s\\}";
	public static final String SUBPARAMETER_SPLITTER = "\\{ss\\}";

	public static final String COL_ID = "questionId";
	public static final String COL_CATEGORY = "questionCategory";
	public static final String COL_SUBCATEGORY = "questionSubategory";
	public static final String COL_TYPE = "questionType";
	public static final String COL_MAIN_TEXT = "mainText";
	public static final String COL_PARAMETERS_TEXT = "parametersText";
	public static final String COL_STATUS = "questionStatus";
	public static final String COL_ANSWER = "questionAnswer";
	public static final String COL_LOCATION_LATITUDE = "questionLocationLatitude";
	public static final String COL_LOCATION_LONGITUDE = "questionLocationLongitude";
	public static final String COL_LOCATION_ALTITUDE = "questionLocationAltitude";
	public static final String COL_LOCATION_ACCURACY = "questionLocationAccuracy";
	public static final String COL_TIMESTAMP = "questionTimestamp";
	public static final String COL_QUESTIONS_VERSION = "questionQestionsVersion";

	public static final String STATUS_ASKED = "questionAsked";
	public static final String STATUS_ASKED_DISMISSED = "questionAskedDismissed";
	public static final String STATUS_ANSWERED = "questionAnswered";

	public static final String CATEGORY_MIND_WANDERING = "mindWandering";
	public static final String CATEGORY_CURRENT_ACTIVITY = "currentActivity";
	public static final String CATEGORY_AFFECTIVE_STATE = "affectiveState";

	public static final Set<String> CATEGORIES_WITH_SUBCATEGORIES =
			new HashSet<String>(Arrays.asList(new String[] {"mindWandering"}));
	public static final String SUBCATEGORY_CONTENT = "content";
	public static final String SUBCATEGORY_QUALITIES = "qualities";

	public static final String TYPE_SLIDER = "slider";
	public static final String TYPE_MULTIPLE_CHOICE = "multipleChoice";
	public static final String TYPE_SINGLE_CHOICE = "singleChoice";

	// constructor : sets default values
	public Question(Context context) {

		// Debug
		Log.d(TAG, "[fn] Question (argset 1: small)");

		initVars();
		_questionsVersion = QuestionsStorage.getInstance(context).getQuestionsVersion();
	}

	// constructor from fields values
	public Question(Context context, String id, String category, String subcategory, String type,
			String mainText, String parametersText) {

		// Debug
		Log.d(TAG, "[fn] Question (argset 2: full)");

		initVars();
		setId(id);
		setCategory(category);
		setSubcategory(subcategory);
		setType(type);
		setMainText(mainText);
		setParametersText(parametersText);
		setQuestionsVersion(QuestionsStorage.getInstance(context).getQuestionsVersion());
	}

	// default initialization of a question
	private void initVars() {

		// Debug
		Log.d(TAG, "[fn] initVars");

		_id = null;
		_category = null;
		_subcategory = null;
		_type = null;
		_mainText = null;
		_parametersText = null;
		_status = null;
		_answer = null;
		_locationLatitude = -1;
		_locationLongitude = -1;
		_locationAltitude = -1;
		_locationAccuracy = -1;
		_timestamp = -1;
		_questionsVersion = -1;
		gson = new Gson();
	}

	//-------------------------- set / get functions

	public String getId() {

		// Verbose
		Log.v(TAG, "[fn] getId");

		return _id;
	}

	public void setId(String id) {

		// Verbose
		Log.v(TAG, "[fn] setId");

		_id = id;
	}

	public String getCategory() {

		// Verbose
		Log.v(TAG, "[fn] getCategory");

		return _category;
	}

	public void setCategory(String category) {

		// Verbose
		Log.v(TAG, "[fn] setCategory");

		_category = category;
	}

	public String getSubcategory() {

		// Verbose
		Log.v(TAG, "[fn] getSubcategory");

		return _subcategory;
	}

	public void setSubcategory(String subcategory) {

		// Verbose
		Log.v(TAG, "[fn] setSubcategory");

		_subcategory = subcategory;
	}

	public String getType() {

		// Verbose
		Log.v(TAG, "[fn] getType");

		return _type;
	}

	private Type getTypeType() {

		// Debug
		Log.d(TAG, "[fn] getTypeType");

		if (_type == null) {
			throw new RuntimeException("Question type not set");
		} else if (_type.equals(TYPE_MULTIPLE_CHOICE)) {
			return MultipleChoiceAnswer.class;
		} else if (_type.equals(TYPE_SLIDER)) {
			return SliderAnswer.class;
		} else {
			throw new RuntimeException("Question type not set");
		}
	}

	private Answer newAnswerType() {

		// Debug
		Log.d(TAG, "[fn] newAnswerType");

		if (_type == null) {
			throw new RuntimeException("Question type not set");
		} else if (_type.equals(TYPE_MULTIPLE_CHOICE)) {
			return new MultipleChoiceAnswer();
		} else if (_type.equals(TYPE_SLIDER)) {
			return new SliderAnswer();
		} else {
			throw new RuntimeException("Question type not set");
		}
	}

	public void setType(String type) {

		// Verbose
		Log.v(TAG, "[fn] setType");

		_type = type;
	}

	public String getStatus() {

		// Verbose
		Log.v(TAG, "[fn] getStatus");

		return _status;
	}

	public void setStatus(String status) {

		// Verbose
		Log.v(TAG, "[fn] setStatus");

		_status = status;
	}

	public String getMainText() {

		// Verbose
		Log.v(TAG, "[fn] getMainText");

		return _mainText;
	}

	public void setMainText(String mainText) {

		// Verbose
		Log.v(TAG, "[fn] setMainText");

		_mainText = mainText;
	}

	public String getParametersText() {

		// Verbose
		Log.v(TAG, "[fn] getParametersText");

		return _parametersText;
	}

	public void setParametersText(String parametersText) {

		// Verbose
		Log.v(TAG, "[fn] setParametersText");

		_parametersText = parametersText;
	}

	public String getAnswer() {

		// Debug
		Log.d(TAG, "[fn] getAnswer");

		if (_answer != null) {
			return _answer.toJson();
		} else {
			return null;
		}
	}

	private void setAnswer(Answer answer) {

		// Verbose
		Log.v(TAG, "[fn] setAnswer (from Answer)");

		_answer = answer;
	}

	public void setAnswer(String jsonAnswer) {

		// Debug
		Log.d(TAG, "[fn] setAnswer (from String)");

		if (jsonAnswer != null && jsonAnswer.length() != 0) {
			Type typeType = getTypeType();
			Answer answer = gson.fromJson(jsonAnswer, typeType);
			setAnswer(answer);
		}
	}

	public double getLocationLatitude() {

		// Verbose
		Log.v(TAG, "[fn] getLocationLatitude");

		return _locationLatitude;
	}

	public double getLocationLongitude() {

		// Verbose
		Log.v(TAG, "[fn] getLocationLongitude");

		return _locationLongitude;
	}

	public double getLocationAltitude() {

		// Verbose
		Log.v(TAG, "[fn] getLocationAltitude");

		return _locationAltitude;
	}

	public double getLocationAccuracy() {

		// Verbose
		Log.v(TAG, "[fn] getLocationAccuracy");

		return _locationAccuracy;
	}

	public void setLocationLatitude(double locationLatitude) {

		// Verbose
		Log.v(TAG, "[fn] setLocationLatitude");

		_locationLatitude = locationLatitude;
	}

	public void setLocationLongitude(double locationLongitude) {

		// Verbose
		Log.v(TAG, "[fn] setLocationLongitude");

		_locationLongitude = locationLongitude;
	}

	public void setLocationAltitude(double locationAltitude) {

		// Verbose
		Log.v(TAG, "[fn] setLocationAltitude");

		_locationAltitude = locationAltitude;
	}

	public void setLocationAccuracy(double locationAccuracy) {

		// Verbose
		Log.v(TAG, "[fn] setLocationAccuracy");

		_locationAccuracy = locationAccuracy;
	}

	public void setLocation(Location location) {

		// Debug
		Log.d(TAG, "[fn] setLocation");

		if (location != null) {
			_locationLatitude = location.getLatitude();
			_locationLongitude = location.getLongitude();
			_locationAltitude = location.getAltitude();
			_locationAccuracy = location.getAccuracy();
		}
	}

	public long getTimestamp() {

		// Verbose
		Log.v(TAG, "[fn] getTimestamp");

		return _timestamp;
	}

	public void setTimestamp(long timestamp) {

		// Verbose
		Log.v(TAG, "[fn] setTimestamp");

		_timestamp = timestamp;
	}

	public int getQuestionsVersion() {

		// Verbose
		Log.v(TAG, "[fn] getQuestionsVersion");

		return _questionsVersion;
	}

	public void setQuestionsVersion(int questionsVersion) {

		// Verbose
		Log.v(TAG, "[fn] setQuestionsVersion");

		_questionsVersion = questionsVersion;
	}

	// Generating views for subsequent display of questions
	// Manage ui and fills answer fields of the class


	// Select questions by tags.
	// Tags only used to identify subquestions when they exist
	protected static ArrayList<View> getViewsByTag(ViewGroup root, String tag){

		// Debug
		Log.d(TAG, "[fn] getViewsByTag");

		ArrayList<View> views = new ArrayList<View>();
		final int childCount = root.getChildCount();
		for (int i = 0; i < childCount; i++) {
			final View child = root.getChildAt(i);
			if (child instanceof ViewGroup) {
				views.addAll(getViewsByTag((ViewGroup) child, tag));
			}

			final Object tagObj = child.getTag();
			if (tagObj != null && tagObj.equals(tag)) {
				views.add(child);
			}

		}
		return views;
	}

	public ArrayList<View> createViews(Context context) {

		// Debug
		Log.d(TAG, "[fn] createViews");

		Context _context = context;
		LayoutInflater inflater = (LayoutInflater)_context.getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);
		if (_type.equals(TYPE_SLIDER)) {
			return createViewsSlider(inflater);
			//		} else if (_type.equals(TYPE_SINGLE_CHOICE)) {
			//			return createViewsSingleChoice(inflater);
		} else if (_type.equals(TYPE_MULTIPLE_CHOICE)) {
			return createViewsMultipleChoice(inflater);
		}
		return null;
	}

	private ArrayList<View> createViewsSlider(LayoutInflater inflater) {

		// Debug
		Log.d(TAG, "[fn] createViewsSlider");

		ArrayList<View> views = new ArrayList<View>();
		ArrayList<String> mainTexts = getParsedMainText();
		ArrayList<ArrayList<String>> allParametersTexts = getParsedParametersText();

		Iterator<String> mtIt = mainTexts.iterator();
		Iterator<ArrayList<String>> ptsIt = allParametersTexts.iterator();

		while (mtIt.hasNext()) {
			String mainText = mtIt.next();
			final ArrayList<String> parametersTexts = ptsIt.next();

			View view = inflater.inflate(R.layout.question_slider, null);
			TextView qText = (TextView)view.findViewById(R.id.question_slider_mainText);
			qText.setText(mainText);
			TextView leftHintText = (TextView)view.findViewById(R.id.question_slider_leftHint);
			leftHintText.setText(parametersTexts.get(0));
			TextView rightHintText = (TextView)view.findViewById(R.id.question_slider_rightHint);
			rightHintText.setText(parametersTexts.get(parametersTexts.size() - 1));
			SeekBar seekBar = (SeekBar)view.findViewById(R.id.question_slider_seekBar);
			final TextView selectedSeek = (TextView)view.findViewById(R.id.question_slider_selectedSeek);
			final int maxSeek = parametersTexts.size();
			OnSeekBarChangeListener listener = new OnSeekBarChangeListener() {

				@Override
				public void onProgressChanged(SeekBar seekBar, int progress,
						boolean fromUser) {
					int index = (int)FloatMath.floor((progress / 101f) * maxSeek);
					selectedSeek.setText(parametersTexts.get(index));
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			};
			seekBar.setOnSeekBarChangeListener(listener);

			views.add(view);
		}

		return views;
	}

	private ArrayList<View> createViewsMultipleChoice(LayoutInflater inflater) {

		// Debug
		Log.d(TAG, "[fn] createViewsMultipleChoice");

		ArrayList<View> views = new ArrayList<View>();
		ArrayList<String> mainTexts = getParsedMainText();
		ArrayList<ArrayList<String>> allParametersTexts = getParsedParametersText();

		Iterator<String> mtIt = mainTexts.iterator();
		Iterator<ArrayList<String>> ptsIt = allParametersTexts.iterator();

		while (mtIt.hasNext()) {
			String mainText = mtIt.next();
			final ArrayList<String> parametersTexts = ptsIt.next();

			View view = inflater.inflate(R.layout.question_multiple_choice, null);
			TextView qText = (TextView)view.findViewById(R.id.question_multiple_choice_mainText);
			qText.setText(mainText);
			final CheckBox otherCheck = (CheckBox)view.findViewById(R.id.question_multiple_choices_otherCheckBox);
			final EditText otherEdit = (EditText)view.findViewById(R.id.question_multiple_choices_otherEditText);
			OnCheckedChangeListener otherCheckListener = new OnCheckedChangeListener() {

				@Override
				public void onCheckedChanged(CompoundButton buttonView,
						boolean isChecked) {
					if (isChecked) {
						otherEdit.requestFocus();
					} else {
						((LinearLayout)otherEdit.getParent()).requestFocus();
						otherEdit.setText("");
					}
				}

			};
			otherCheck.setOnCheckedChangeListener(otherCheckListener);

			View.OnClickListener otherEditClickListener = new View.OnClickListener() {

				@Override
				public void onClick(View v) {
					otherCheck.setChecked(true);
				}

			};
			otherEdit.setOnClickListener(otherEditClickListener);

			View.OnFocusChangeListener otherEditFocusListener = new View.OnFocusChangeListener() {

				@Override
				public void onFocusChange(View v, boolean hasFocus) {
					if (hasFocus) {
						otherCheck.setChecked(true);
					}
				}
			};
			otherEdit.setOnFocusChangeListener(otherEditFocusListener);

			final Context context = inflater.getContext();
			View.OnKeyListener onSoftKeyboardDonePress = new View.OnKeyListener() {

				@Override
				public boolean onKey(View v, int keyCode, KeyEvent event) {
					if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
						InputMethodManager inputMM = (InputMethodManager)context.getSystemService(
								Context.INPUT_METHOD_SERVICE);
						inputMM.hideSoftInputFromWindow(otherEdit.getApplicationWindowToken(), 0);
					}
					return false;
				}
			};
			otherEdit.setOnKeyListener(onSoftKeyboardDonePress);

			LinearLayout checksLayout = (LinearLayout)view.findViewById(R.id.question_multiple_choice_rootChoices);
			Iterator<String> ptIt = parametersTexts.iterator();

			while (ptIt.hasNext()) {
				String parameter = ptIt.next();
				CheckBox checkBox = (CheckBox)inflater.inflate(R.layout.question_multiple_choices_item, null);
				checkBox.setText(parameter);
				checksLayout.addView(checkBox);
			}

			views.add(view);
		}

		return views;
	}

	// --- Validation functions

	public boolean validate(Context context, LinearLayout questionsLinearLayout) {

		// Debug
		Log.d(TAG, "[fn] validate");

		if (_type.equals(TYPE_SLIDER)) {
			return validateSlider(context, questionsLinearLayout);
		} else if (_type.equals(TYPE_MULTIPLE_CHOICE)) {
			return validateMultipleChoice(context, questionsLinearLayout);
		}
		return false;
	}

	// For slider, checks whether or not sliders were kept untouched
	private boolean validateSlider(Context context, LinearLayout questionsLinearLayout) {

		// Debug
		Log.d(TAG, "[fn] validateSlider");

		ArrayList<View> subquestions = getViewsByTag(questionsLinearLayout, "subquestion");
		boolean isMultiple = subquestions.size() > 1 ? true : false;
		Iterator<View> subquestionsIt = subquestions.iterator();

		while (subquestionsIt.hasNext()) {
			TextView selectedSeek = (TextView)subquestionsIt.next().
					findViewById(R.id.question_slider_selectedSeek);
			if (selectedSeek.getText().equals(
					context.getString(R.string.questionSlider_please_slide))) {
				Toast.makeText(context,
						context.getString(isMultiple ? R.string.questionSlider_sliders_untouched_multiple :
							R.string.questionSlider_sliders_untouched_single),
							Toast.LENGTH_SHORT).show();
				return false;
			}
		}

		return true;
	}

	// This will behave badly when there are multiple sub-multiplechoice questions
	private boolean validateMultipleChoice(Context context, LinearLayout questionsLinearLayout) {

		// Debug
		Log.d(TAG, "[fn] validateMultipleChoice");

		ArrayList<View> subquestions = getViewsByTag(questionsLinearLayout, "subquestion");
		Iterator<View> subquestionsIt = subquestions.iterator();

		while (subquestionsIt.hasNext()) {

			LinearLayout subquestionLinearLayout = (LinearLayout)subquestionsIt.next();
			LinearLayout rootChoices = (LinearLayout)subquestionLinearLayout.findViewById(
					R.id.question_multiple_choice_rootChoices);
			int childCount = rootChoices.getChildCount();
			boolean hasCheck = false;
			CheckBox otherCheck = (CheckBox)subquestionLinearLayout.findViewById(
					R.id.question_multiple_choices_otherCheckBox);
			boolean hasOtherCheck = otherCheck.isChecked();

			if (hasOtherCheck) {
				EditText otherEditText = (EditText)subquestionLinearLayout.findViewById(
						R.id.question_multiple_choices_otherEditText);
				if (otherEditText.getText().length() == 0) {
					Toast.makeText(context,
							context.getString(R.string.questionMultipleChoices_other_please_fill),
							Toast.LENGTH_SHORT).show();
					return false;
				}
			}

			for (int i = 0; i < childCount; i++) {
				CheckBox child = (CheckBox)rootChoices.getChildAt(i);
				if (child.isChecked()) {
					hasCheck = true;
					break;
				}
			}

			if (!hasCheck && !hasOtherCheck) {
				Toast.makeText(context,
						context.getString(R.string.questionMultipleChoices_please_check_one),
						Toast.LENGTH_SHORT).show();
				return false;
			}
		}

		return true;
	}

	// Parsing subfunctions

	private ArrayList<String> parseString(String toParse, String sep) {

		// Verbose
		Log.v(TAG, "[fn] parseString");

		return new ArrayList<String>(Arrays.asList(toParse.split(sep)));
	}

	private ArrayList<String> getParsedMainText() {

		// Debug
		Log.d(TAG, "[fn] getParsedMainText");

		return parseString(_mainText, PARAMETER_SPLITTER);
	}

	private ArrayList<ArrayList<String>> getParsedParametersText() {

		// Debug
		Log.d(TAG, "[fn] getParsedParametersText");

		ArrayList<ArrayList<String>> parsedParametersText = new ArrayList<ArrayList<String>>();
		Iterator<String> preParsedIt = parseString(_parametersText,
				PARAMETER_SPLITTER).iterator();
		ArrayList<String> subParameters;
		while (preParsedIt.hasNext()) {
			subParameters = parseString(preParsedIt.next(), SUBPARAMETER_SPLITTER);
			parsedParametersText.add(subParameters);
		}

		return parsedParametersText;
	}

	public void saveAnswers(LinearLayout questionLinearLayout) {

		// Debug
		Log.d(TAG, "[fn] saveAnswers");

		Answer answer = newAnswerType();
		answer.getAnswersFromLayout(questionLinearLayout);
		setAnswer(answer);
	}
}
