// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class YouLoseMainMenuButton : MonoBehaviour
{
    public Button MainMenuButton;
    public GameObject Indicator;

    // RVA: 0x29fd
    private void Start()
    {
        this.Indicator.SetActive(false);
        this.MainMenuButton.onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x2a27
    private void TaskOnClick()
    {
        this.Indicator.SetActive(true);
        SceneManager.LoadScene("Main Menu");
    }

}
