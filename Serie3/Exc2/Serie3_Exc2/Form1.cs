using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Serie3_Exc2 {
    public partial class Form1 : Form {
        public Form1() {
            InitializeComponent();
        }

        private void Form1_Load(object sender, EventArgs e) {

        }

        private CancellationTokenSource cts = null;

        private void cancelButton_Click(object sender, EventArgs e) {
            if(cts != null) {
                cts.Cancel();
                outputTextBox.Text += "Operation Cancelled - Trying to stop!\n";
                statusTextBox.Text = "Operation Cancelled - Trying to stop!";
            }
        }

        private async void goButton_Click(object sender, EventArgs e) {
            if(string.IsNullOrWhiteSpace(stringTextBox.Text)) {
                statusTextBox.Text = "Insert string to search!";
            } else if(string.IsNullOrWhiteSpace(pathTextBox.Text)) {
                statusTextBox.Text = "Insert path to folder!";
            } else {
                outputTextBox.Clear();
                goButton.Enabled = false;
                cancelButton.Enabled = true;
                statusTextBox.Text = "Processing...";
                try {
                    cts = new CancellationTokenSource();
                    await Services.Search(
                        pathTextBox.Text,
                        stringTextBox.Text,
                        (filename, idx, line) => outputTextBox.Invoke(new Print(PrintIntoOutput), new object[] { filename, idx, line }),
                        cts.Token);
                    statusTextBox.Text = "Operation finished successfully!";
                } catch(TaskCanceledException) {
                    statusTextBox.Text = "Operation finished by cancelation!";
                } catch(Exception ex) {
                    statusTextBox.Text = "Error: " + ex.Message;
                } finally {
                    cts = null;
                    goButton.Enabled = true;
                    cancelButton.Enabled = false;
                }
            }
        }

        private delegate void Print(string filename, int idx, string line);

        private void PrintIntoOutput(string filename, int idx, string line) {
            outputTextBox.Text += "\"" + filename + "\"" + "[" + idx + "] = " + line + "\n";
        }
    }
}
