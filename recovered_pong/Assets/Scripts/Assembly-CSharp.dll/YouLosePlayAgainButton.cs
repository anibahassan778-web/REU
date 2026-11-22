// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class YouLosePlayAgainButton : MonoBehaviour
{
    public Button MainMenuButton;
    public GameObject Indicator2;

    // RVA: 0x2a47
    private void Start()
    {
        this.Indicator2.SetActive(false);
        this.MainMenuButton.onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x2a71
    private void TaskOnClick()
    {
        this.Indicator2.SetActive(true);
        SceneManager.LoadScene("GameScene");
    }

}
