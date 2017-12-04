package com.sapuseven.untis.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TextInputEditText;
import android.support.design.widget.TextInputLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sapuseven.untis.R;
import com.sapuseven.untis.adapter.AdapterCheckBoxGridView;
import com.sapuseven.untis.adapter.AdapterItemRoomFinder;
import com.sapuseven.untis.adapter.AdapterRoomFinder;
import com.sapuseven.untis.utils.DateOperations;
import com.sapuseven.untis.utils.ElementName;
import com.sapuseven.untis.utils.ListManager;
import com.sapuseven.untis.utils.TimegridUnitManager;
import com.sapuseven.untis.utils.Timetable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.sapuseven.untis.fragment.FragmentTimetable.ID_GET_TIMETABLE;
import static com.sapuseven.untis.utils.Authentication.getAuthElement;
import static com.sapuseven.untis.utils.DateOperations.addDaysToInt;
import static com.sapuseven.untis.utils.DateOperations.getStartDateFromWeek;
import static com.sapuseven.untis.utils.ElementName.ROOM;
import static com.sapuseven.untis.utils.SessionInfo.getElemTypeName;
import static com.sapuseven.untis.utils.ThemeUtils.setupTheme;

public class ActivityRoomFinder extends AppCompatActivity implements View.OnClickListener {
	private int mRoomListMargins;
	private JSONObject mUserDataList;
	private AlertDialog mDialog;
	private ArrayList<AdapterItemRoomFinder> mRoomList;
	private AdapterRoomFinder mRoomAdapter;
	private int mCurrentHourIndex = 0;
	private int mHourIndexOffset;
	private ArrayList<Request> mRequestQueue;
	private ArrayList<String> mRefreshingItems;
	private RecyclerView mRecyclerView;
	private TextView mCurrentHour;
	private int mMaxHourIndex = 0;

	public static ArrayList<String> getRooms(Context context, boolean includeDisable) {
		ArrayList<String> roomList = new ArrayList<>();

		if (includeDisable)
			roomList.add(context.getString(R.string.preference_note_disable));

		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					context.openFileInput("roomList.txt")));
			String name;
			while ((name = reader.readLine()) != null) {
				for (int i = 0; i < 2; i++)
					reader.readLine();
				roomList.add(name);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return roomList;
	}

	public static String getRoomStates(Context context, String name) {
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					context.openFileInput("roomList.txt")));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.equals(name))
					return reader.readLine();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		setupTheme(this, true);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_room_finder);

		mRoomListMargins = (int) (12 * getResources().getDisplayMetrics().density + 0.5f);

		ListManager listManager = new ListManager(getApplicationContext());
		try {
			mUserDataList = new JSONObject(listManager.readList("userData", false));
		} catch (JSONException e) {
			e.printStackTrace();
		}

		mRecyclerView = findViewById(R.id.lvRoomList);
		setupNoRoomsIndicator();
		setupRoomList(mRecyclerView);
		setupHourSelector();
	}

	private void setupHourSelector() {
		findViewById(R.id.btnNextHour).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mCurrentHourIndex + mHourIndexOffset < mMaxHourIndex) {
					mHourIndexOffset++;
					refreshRoomList();
				}
			}
		});

		findViewById(R.id.btnPrevHour).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mCurrentHourIndex + mHourIndexOffset > 0) {
					mHourIndexOffset--;
					refreshRoomList();
				}
			}
		});

		findViewById(R.id.tvCurrentHour).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				mHourIndexOffset = 0;
				refreshRoomList();
			}
		});
	}

	private void setupNoRoomsIndicator() {
		TextView tv = findViewById(R.id.tvNoRooms);
		String text = tv.getText().toString();
		if (text.contains("+")) {
			SpannableString ss = new SpannableString(text);
			Drawable img = ContextCompat.getDrawable(this, R.drawable.ic_add_circle);
			img.setBounds(0, 0, img.getIntrinsicWidth(), img.getIntrinsicHeight());
			ss.setSpan(new ImageSpan(img, ImageSpan.ALIGN_BOTTOM),
					text.indexOf("+"), text.indexOf("+") + 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
			tv.setText(ss);
		}
	}

	private void setupRoomList(RecyclerView listView) {
		mRoomList = new ArrayList<>();
		mRequestQueue = new ArrayList<>();
		mRefreshingItems = new ArrayList<>();

		listView.setLayoutManager(new LinearLayoutManager(this));
		mRoomAdapter = new AdapterRoomFinder(this, mRoomList);
		listView.setAdapter(mRoomAdapter);

		reload();

		FloatingActionButton myFab = findViewById(R.id.fabAddRoomWatcher);
		myFab.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showItemList();
			}
		});
	}

	private void reload() {
		mRoomList.clear();
		try {
			BufferedReader reader =
					new BufferedReader(new InputStreamReader(openFileInput("roomList.txt")));
			String name;
			while ((name = reader.readLine()) != null) {
				AdapterItemRoomFinder roomItem =
						new AdapterItemRoomFinder(this, name, mRefreshingItems.contains(name));
				String binaryData = reader.readLine();
				boolean[] states = new boolean[binaryData.length()];
				for (int i = 0; i < states.length; i++)
					states[i] = binaryData.charAt(i) == '1';
				roomItem.setStates(states);
				mMaxHourIndex = states.length - 1;
				roomItem.setDate(Long.parseLong(reader.readLine()));
				mRoomList.add(roomItem);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		for (Request r : mRequestQueue) {
			if (!r.isRefreshOnly())
				mRoomList.add(new AdapterItemRoomFinder(this, r.getDisplayName(), true));
			mRoomAdapter.notifyItemInserted(mRoomList.size() - 1);
		}

		refreshRoomList();
	}

	private void refreshRoomList() {
		if (mRoomList.isEmpty())
			findViewById(R.id.tvNoRooms).setVisibility(View.VISIBLE);
		else
			findViewById(R.id.tvNoRooms).setVisibility(View.GONE);

		Collections.sort(mRoomList);
		mRoomAdapter.notifyDataSetChanged();
		displayCurrentHour();
	}

	private void showItemList() {
		try {
			final ElementName elementName = new ElementName(ROOM).setUserDataList(mUserDataList);
			LinearLayout content = new LinearLayout(this);
			content.setOrientation(LinearLayout.VERTICAL);

			final List<String> list = new ArrayList<>();
			JSONArray roomList = mUserDataList.optJSONObject("masterData").optJSONArray("rooms");
			for (int i = 0; i < roomList.length(); i++)
				list.add(roomList.getJSONObject(i).getString("name"));
			Collections.sort(list, new Comparator<String>() {
				@Override
				public int compare(String s1, String s2) {
					return s1.compareToIgnoreCase(s2);
				}
			});

			final AdapterCheckBoxGridView adapter = new AdapterCheckBoxGridView(this, list);
			TextInputLayout titleContainer = new TextInputLayout(this);
			LinearLayout.LayoutParams searchFieldParams =
					new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
							ViewGroup.LayoutParams.WRAP_CONTENT);
			searchFieldParams.setMargins(mRoomListMargins, mRoomListMargins, mRoomListMargins, 0);
			titleContainer.setLayoutParams(searchFieldParams);

			GridView gridView = new GridView(this);
			LinearLayout.LayoutParams gridParams =
					new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
			gridView.setLayoutParams(gridParams);
			gridView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
			gridView.setAdapter(adapter);
			gridView.setNumColumns(3);

			TextInputEditText searchField = new TextInputEditText(this);
			searchField.setHint(R.string.hint_add_room);
			searchField.addTextChangedListener(new TextWatcher() {
				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					adapter.getFilter().filter(s.toString());
				}

				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void afterTextChanged(Editable s) {
				}
			});
			titleContainer.addView(searchField);
			Button selectAll = (Button) getLayoutInflater()
					.inflate(R.layout.borderless_button, null);
			selectAll.setText(R.string.add);
			final ActivityRoomFinder context = this;
			selectAll.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					for (String item : adapter.getSelectedItems())
						addRoom(new AdapterItemRoomFinder(context, item, true),
								(Integer) elementName.findFieldByValue("name", item, "id"));
					mDialog.dismiss();
					executeRequestQueue();
				}
			});

			content.addView(titleContainer);
			content.addView(gridView);
			content.addView(selectAll);

			mDialog = new AlertDialog.Builder(this)
					.setView(content)
					.create();
			mDialog.show();
		} catch (JSONException e) {
			e.printStackTrace();
			new AlertDialog.Builder(this)
					.setTitle(getString(R.string.error))
					.setMessage(e.getMessage())
					.setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
						}
					})
					.show();
		}
	}

	private void addRoom(AdapterItemRoomFinder item, Integer roomID) {
		if (mRoomList.contains(item))
			return;

		int startDateFromWeek = Integer.parseInt(new SimpleDateFormat("yyyyMMdd", Locale.US)
				.format(getStartDateFromWeek(Calendar.getInstance(), 0).getTime()));

		SharedPreferences prefs = getSharedPreferences("login_data", MODE_PRIVATE);
		Request request = new Request(prefs.getString("url", null), item.getName());
		request.setSchool(prefs.getString("school", null));
		request.setParams("[{\"id\":\"" + roomID + "\"," +
				"\"type\":\"" + getElemTypeName(ROOM) + "\"," +
				"\"startDate\":" + startDateFromWeek + "," +
				"\"endDate\":" + addDaysToInt(startDateFromWeek, 4) + "," +
				"\"masterDataTimestamp\":" + System.currentTimeMillis() + "," +
				getAuthElement(prefs.getString("user", ""), prefs.getString("key", "")) +
				"}]");
		mRequestQueue.add(request);

		refreshRoomList();
	}

	private void executeRequestQueue() {
		reload();

		if (mRequestQueue.size() > 0 && mRequestQueue.get(0).getStatus() == AsyncTask.Status.PENDING)
			mRequestQueue.get(0).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

	public int getCurrentHourIndex() {
		if (mCurrentHourIndex > 0)
			return mCurrentHourIndex + mHourIndexOffset;

		int index = 0;

		JSONArray days = null;
		try {
			days = new JSONObject(new ListManager(getApplication())
					.readList("userData", false))
					.getJSONObject("masterData")
					.getJSONObject("timeGrid")
					.getJSONArray("days");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		TimegridUnitManager unitManager = new TimegridUnitManager(days);

		Calendar cNow = Calendar.getInstance();
		Calendar cToCompare = Calendar.getInstance();

		int startDateFromWeek = Integer.parseInt(new SimpleDateFormat("yyyyMMdd", Locale.US)
				.format(DateOperations.getStartDateFromWeek(Calendar.getInstance(), 0).getTime()));

		for (int i = 0; i < unitManager.getNumberOfDays() * unitManager.getMaxHoursPerDay(); i++) {
			String dateTime = addDaysToInt(startDateFromWeek, i / unitManager.getMaxHoursPerDay(),
					new SimpleDateFormat("yyyy-MM-dd'T'", Locale.US))
					+ String.format("%1$5s", unitManager.getUnits().get(i % unitManager
					.getMaxHoursPerDay()).getDisplayEndTime()).replace(' ', '0') + "Z";

			try {
				cToCompare.setTime(DateOperations.parseFromISO(dateTime));
			} catch (ParseException e) {
				e.printStackTrace();
			}

			if (cNow.getTimeInMillis() > cToCompare.getTimeInMillis())
				index++;
			else
				break;
		}

		if (index == unitManager.getNumberOfDays() * unitManager.getMaxHoursPerDay())
			index = 0;

		Log.d("RoomFinder", "Current Hour Index: " + index);
		mCurrentHourIndex = Math.max(index, 0);
		return mCurrentHourIndex + mHourIndexOffset;
	}

	public void deleteItem(final int position) {
		new AlertDialog.Builder(this)
				.setTitle(getString(R.string.delete_item_title, mRoomList.get(position).getName()))
				.setMessage(R.string.delete_item_text)
				.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						try {
							if (deleteItem(mRoomList.get(position).getName())) {
								mRoomList.remove(position);
								mRoomAdapter.notifyItemRemoved(position);
								refreshRoomList();
							}
						} catch (IOException e) {
							Snackbar.make(mRecyclerView, getString(R.string.snackbar_error,
									e.getMessage()),
									Snackbar.LENGTH_LONG).setAction("OK", null).show();
						}
					}
				})
				.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				})
				.create()
				.show();
	}

	private boolean deleteItem(final String name) throws IOException {
		File inputFile = new File(getFilesDir(), "roomList.txt");
		File tempFile = new File(getFilesDir(), "roomList.txt.tmp");

		BufferedReader reader = new BufferedReader(new InputStreamReader(
				openFileInput("roomList.txt"), "UTF-8"));
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
				openFileOutput("roomList.txt.tmp", MODE_APPEND), "UTF-8"));

		String currentLine;

		while ((currentLine = reader.readLine()) != null) {
			String trimmedLine = currentLine.trim();

			if (trimmedLine.equals(name)) {
				reader.readLine();
				reader.readLine();
				continue;
			}

			writer.write(currentLine);
			writer.newLine();
			writer.write(reader.readLine());
			writer.newLine();
			writer.write(reader.readLine());
			writer.newLine();
		}
		reader.close();
		writer.close();

		return inputFile.delete() && tempFile.renameTo(inputFile);
	}

	public void refreshItem(final int position) {
		new AlertDialog.Builder(this)
				.setTitle(R.string.refresh_item_title)
				.setMessage(getString(R.string.refresh_item_text))
				.setPositiveButton(R.string.refresh_this_item, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						refreshItemData(position);
						mRoomAdapter.notifyDataSetChanged();
						executeRequestQueue();
						dialog.dismiss();
					}
				})
				.setNeutralButton(R.string.refresh_all_items, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						for (int i = 0; i < mRoomList.size(); i++)
							if (mRoomList.get(i).isOutdated())
								refreshItemData(i);
						mRoomAdapter.notifyDataSetChanged();
						executeRequestQueue();
						dialog.dismiss();
					}
				})
				.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				})
				.create()
				.show();
	}

	private void refreshItemData(final int position) {
		mRoomList.get(position).setLoading();

		final int startDateFromWeek = Integer.parseInt(new SimpleDateFormat("yyyyMMdd", Locale.US)
				.format(getStartDateFromWeek(Calendar.getInstance(), 0).getTime()));
		final SharedPreferences prefs = getSharedPreferences("login_data", MODE_PRIVATE);
		final ElementName elementName = new ElementName(ROOM).setUserDataList(mUserDataList);

		Request request = new Request(prefs.getString("url", null),
				mRoomList.get(position).getName());
		request.setSchool(prefs.getString("school", null));
		request.setParams("[{\"id\":\"" + elementName.findFieldByValue("name",
				mRoomList.get(position).getName(), "id") + "\"," +
				"\"type\":\"" + getElemTypeName(ROOM) + "\"," +
				"\"startDate\":" + startDateFromWeek + "," +
				"\"endDate\":" + addDaysToInt(startDateFromWeek, 4) + "," +
				"\"masterDataTimestamp\":" + System.currentTimeMillis() + "," +
				getAuthElement(prefs.getString("user", ""), prefs.getString("key", "")) +
				"}]");
		request.refreshOnly();
		mRequestQueue.add(request);
		mRefreshingItems.add(mRoomList.get(position).getName());
	}

	private void displayCurrentHour() {
		if (mCurrentHour == null)
			mCurrentHour = findViewById(R.id.tvCurrentHour);

		if (mHourIndexOffset < 0)
			mCurrentHour.setText(getResources().getQuantityString(R.plurals.hour_index_last,
					Math.abs(mHourIndexOffset), Math.abs(mHourIndexOffset)));
		else if (mHourIndexOffset > 0)
			mCurrentHour.setText(getResources().getQuantityString(R.plurals.hour_index_next,
					mHourIndexOffset, mHourIndexOffset));
		else
			mCurrentHour.setText(getString(R.string.hour_index_current));
	}

	@Override
	public void onClick(final View v) {
		int itemPosition = mRecyclerView.getChildLayoutPosition(v);
		AdapterItemRoomFinder item = mRoomList.get(itemPosition);

		Intent intent = new Intent();
		final ElementName elementName = new ElementName(ROOM).setUserDataList(mUserDataList);
		intent.putExtra("elemId", (int) elementName.findFieldByValue("name", item.getName(), "id"));
		intent.putExtra("elemType", ROOM);
		intent.putExtra("displayName", getString(R.string.title_room, item.getName()));
		setResult(RESULT_OK, intent);
		finish();
	}

	private class Request extends AsyncTask<Void, Void, String> {
		private static final String jsonrpc = "2.0";
		private final String method = "getTimetable2017";
		private final String id = ID_GET_TIMETABLE;
		private final String displayName;
		private String url = "";
		private String params = "{}";
		private String school = "";
		private boolean refreshOnly;

		Request(String url, String displayName) {
			this.url = "https://" + url + "/WebUntis/jsonrpc_intern.do";
			this.displayName = displayName;
		}

		void setSchool(String school) {
			this.school = school;
		}

		void setParams(String params) {
			this.params = params;
		}

		@Override
		protected String doInBackground(Void... p1) {
			String result;
			HttpURLConnection urlConnection = null;
			try {
				String url = this.url;
				if (this.school.length() > 0)
					url += "?school=" + this.school;
				urlConnection = (HttpURLConnection) new URL(url).openConnection();

				JSONObject jsonObject = new JSONObject();
				jsonObject.put("id", this.id);
				jsonObject.put("method", this.method);
				jsonObject.put("params", new JSONArray(this.params));
				jsonObject.put("jsonrpc", jsonrpc);

				urlConnection.setDoOutput(true);
				urlConnection.setRequestMethod("POST");
				urlConnection.setRequestProperty("Content-Type", "application/json");
				urlConnection.setRequestProperty("Accept", "application/json");
				urlConnection.connect();

				DataOutputStream wr = new DataOutputStream(urlConnection.getOutputStream());
				wr.writeBytes(jsonObject.toString());
				wr.close();

				int response = urlConnection.getResponseCode();
				if (response >= 200 && response <= 399)
					result = inputStreamToString(urlConnection.getInputStream());
				else
					result = "{}";
			} catch (Exception e) {
				result = "{\"id\":\"" + this.id + "\",\"error\":{\"message\":\"" + e.getMessage()
						.replace("\"", "\\\"") + "\"}}";
			} finally {
				if (urlConnection != null)
					urlConnection.disconnect();
			}
			return result;
		}

		@Override
		protected void onPostExecute(String result) {
			try {
				JSONObject jsonObj = new JSONObject(result);
				if (jsonObj.has("error")) {
					Log.w("error", jsonObj.toString());
					Snackbar.make(mRecyclerView,
							getString(R.string.snackbar_error, jsonObj.getJSONObject("error")
									.getString("message")), Snackbar.LENGTH_LONG)
							.setAction("OK", null).show();
				} else if (jsonObj.has("result")) {
					Timetable timetable = new Timetable(jsonObj.getJSONObject("result"), PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));

					JSONArray dayList = null;
					try {
						dayList = new JSONObject(new ListManager(getApplication())
								.readList("userData", false)).getJSONObject("masterData")
								.getJSONObject("timeGrid").getJSONArray("days");
					} catch (JSONException e) {
						e.printStackTrace();
					}

					TimegridUnitManager unitManager = new TimegridUnitManager(dayList);

					int days = unitManager.getNumberOfDays();
					int hours = unitManager.getMaxHoursPerDay();

					boolean[] states = new boolean[days * hours];

					for (int i = 0; i < states.length; i++) {
						int day = i / hours;
						int hour = i % hours;

						if (timetable.getItems(day, hour).size() > 0)
							states[day * hours + hour] = true;
					}

					StringBuilder binaryData = new StringBuilder();
					for (boolean value : states)
						binaryData.append(value ? '1' : '0');

					if (!TextUtils.isEmpty(binaryData.toString())) {
						if (refreshOnly)
							deleteItem(displayName);

						BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
								openFileOutput("roomList.txt", MODE_APPEND), "UTF-8"));
						writer.write(mRequestQueue.get(0).getDisplayName());
						writer.newLine();
						writer.write(binaryData.toString());
						writer.newLine();
						writer.write(String.valueOf(getStartDateFromWeek(Calendar.getInstance(), 0,
								true).getTimeInMillis()));
						writer.newLine();
						writer.close();
					}

					if (refreshOnly)
						mRefreshingItems.remove(0);

					reload();
					refreshRoomList();
				}
			} catch (JSONException | IOException e) {
				e.printStackTrace();
			}
			mRequestQueue.remove(0);
			executeRequestQueue();
		}

		private String getDisplayName() {
			return displayName;
		}

		private String inputStreamToString(InputStream inputStream) throws IOException {
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
			String line;
			StringBuilder result = new StringBuilder();
			while ((line = bufferedReader.readLine()) != null)
				result.append(line);

			inputStream.close();
			return result.toString();
		}

		boolean isRefreshOnly() {
			return refreshOnly;
		}

		void refreshOnly() {
			this.refreshOnly = true;
		}
	}
}