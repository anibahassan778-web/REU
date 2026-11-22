// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class PauseButton : MonoBehaviour
{
    public Button pauseButton;

    // RVA: 0x26cc
    private void Start()
    {
        this.pauseButton.GetComponent<Button>().onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x26ef
    private void TaskOnClick()
    {
        SceneManager.LoadScene("Main Menu");
    }

}
