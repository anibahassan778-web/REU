// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class PlayToSample : MonoBehaviour
{
    public Button myButton;
    public GameObject Image3;
    public GameObject circle;

    // RVA: 0x29ae
    private void Start()
    {
        this.Image3.SetActive(false);
        this.myButton.GetComponent<Button>().onClick.AddListener(new UnityAction(this.TaskOnClick));
    }

    // RVA: 0x29dd
    private void TaskOnClick()
    {
        this.Image3.SetActive(true);
        SceneManager.LoadScene("GameScene");
    }

}
