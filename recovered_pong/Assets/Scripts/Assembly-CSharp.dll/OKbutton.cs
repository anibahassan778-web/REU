// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class OKbutton : MonoBehaviour
{
    public Button okButton;
    public GameObject Image4;

    // RVA: 0x28ce
    private void Start()
    {
        this.Image4.SetActive(false);
        this.okButton.GetComponent<Button>().onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x28fd
    private void TaskOnClick()
    {
        this.Image4.SetActive(true);
        SceneManager.LoadScene("Main Menu");
    }

}
