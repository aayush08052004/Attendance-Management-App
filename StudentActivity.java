package com.example.attendanceapp;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class StudentActivity extends AppCompatActivity {
    private TextView title, subtitle;
    private ImageButton save;
    private Toolbar toolbar;
    private String className, subjectName;
    private long cid;
    private RecyclerView recyclerView;
    private StudentAdapter adapter;
    private ArrayList<StudentItem> studentItems = new ArrayList<>();
    private DbHelper dbHelper;
    private MyCalendar calendar;

    private static final int REQUEST_PERMISSION_WRITE_EXTERNAL_STORAGE = 1;
    private static final int REQUEST_PERMISSION_READ_EXTERNAL_STORAGE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);

        // Initialize database and calendar
        dbHelper = new DbHelper(this);
        calendar = new MyCalendar();

        // Retrieve data from intent
        Intent intent = getIntent();
        className = intent.getStringExtra("className");
        subjectName = intent.getStringExtra("subjectName");
        cid = intent.getLongExtra("cid", -1);

        if (cid == -1 || className == null || subjectName == null) {
            Toast.makeText(this, "Invalid data received", Toast.LENGTH_SHORT).show();
            finish(); // Close activity
            return;
        }

        setupToolbar();
        setupRecyclerView();
        setupButtons();
        loadStudentsFromCSV(); // Load students from CSV
        loadStatusData();

        // Request permissions
        requestPermissions();
    }

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        title = findViewById(R.id.title_toolbar);
        subtitle = findViewById(R.id.subtitle_toolbar);
        subtitle.setText(calendar.getDate());
        title.setText(className + " | " + subjectName);
        toolbar.inflateMenu(R.menu.student_menu);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
    }

    private void setupRecyclerView() {
        recyclerView = findViewById(R.id.student_recycler);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentAdapter(this, studentItems);
        recyclerView.setAdapter(adapter);
        adapter.setOnItemClickListener(this::changeStatus);
    }

    private void setupButtons() {
        save = findViewById(R.id.save);
        save.setOnClickListener(v -> saveStatus());

        Button downloadButton = findViewById(R.id.download_button);
        downloadButton.setOnClickListener(v -> downloadAttendanceData());
    }

    private void saveStatus() {
        for (StudentItem studentItem : studentItems) {
            String status = studentItem.getStatus();
            if (status.isEmpty()) status = "A"; // Default to Absent if no status

            long value = dbHelper.addStatus(studentItem.getSid(), cid, calendar.getDate(), status);
            if (value == -1) {
                dbHelper.updateStatus(studentItem.getSid(), calendar.getDate(), status);
            }
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_LONG).show();
    }

    private void loadStatusData() {
        for (StudentItem studentItem : studentItems) {
            String status = dbHelper.getStatus(studentItem.getSid(), calendar.getDate());
            studentItem.setStatus(status != null ? status : ""); // Set status or empty
        }
        adapter.notifyDataSetChanged();
    }

    private void loadStudentsFromCSV() {
        InputStream inputStream = getResources().openRawResource(R.raw.students); // Ensure your CSV is correct
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");

                // Ensure you are getting at least 3 parts
                if (parts.length >= 3) {
                    int roll;
                    try {
                        roll = Integer.parseInt(parts[1].trim()); // Parse roll number
                    } catch (NumberFormatException e) {
                         continue; // Skip this entry if roll number is invalid
                    }

                    String name = parts[2].trim();
                    long sid = dbHelper.addStudent(cid, roll, name);
                    studentItems.add(new StudentItem(sid, roll, name));
                }
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error reading CSV file", Toast.LENGTH_SHORT).show();
        }
    }

    private void changeStatus(int position) {
        String currentStatus = studentItems.get(position).getStatus();
        studentItems.get(position).setStatus(currentStatus.equals("P") ? "A" : "P");
        adapter.notifyItemChanged(position);
    }

    private boolean onMenuItemClick(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.show_Calendar) {
            showCalendar();
            return true;
        }
        return false;
    }

    private void showCalendar() {
        calendar.show(getSupportFragmentManager(), "");
        calendar.setOnCalendarOkClickListener(this::onCalendarOkClicked);
    }

    private void onCalendarOkClicked(int year, int month, int day) {
        calendar.setDate(year, month, day);
        subtitle.setText(calendar.getDate());
        loadStatusData();
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            deleteStudent(item.getGroupId());
            return true;
        }
        return super.onContextItemSelected(item);
    }

    private void deleteStudent(int position) {
        dbHelper.deleteStudent(studentItems.get(position).getSid());
        studentItems.remove(position);
        adapter.notifyItemRemoved(position);
    }

    private void downloadAttendanceData() {
        String date = calendar.getDate();
        StringBuilder csvData = new StringBuilder();
        csvData.append("Roll Number,Name,Status\n"); // CSV header

        for (StudentItem studentItem : studentItems) {
            csvData.append(studentItem.getRoll()).append(",")
                    .append(studentItem.getName()).append(",")
                    .append(studentItem.getStatus()).append("\n");
        }

        saveToFile(csvData.toString(), date);
    }

    private void saveToFile(String data, String date) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // For Android 10 and above
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, "Attendance_" + date.replace(".", "_") + ".csv");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    if (outputStream != null) {
                        outputStream.write(data.getBytes());
                        Toast.makeText(this, "Attendance data downloaded to Downloads folder", Toast.LENGTH_LONG).show();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Failed to download data", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            // For devices below Android 10
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(directory, "Attendance_" + date.replace(".", "_") + ".csv");

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(data);
                Toast.makeText(this, "Attendance data downloaded to Downloads folder", Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to download data", Toast.LENGTH_LONG).show();
            }
        }
    }
    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION_READ_EXTERNAL_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}